/**
 * Copyright (C) 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.inject.privatemodules;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import com.google.common.collect.Sets;
import com.google.inject.Binder;
import com.google.inject.Binding;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Provider;
import com.google.inject.Scope;
import com.google.inject.Stage;
import com.google.inject.TypeLiteral;
import com.google.inject.binder.AnnotatedBindingBuilder;
import com.google.inject.binder.AnnotatedConstantBindingBuilder;
import com.google.inject.binder.LinkedBindingBuilder;
import com.google.inject.internal.ProviderMethod;
import com.google.inject.internal.ProviderMethodsModule;
import com.google.inject.internal.SourceProvider;
import com.google.inject.internal.UniqueAnnotations;
import com.google.inject.matcher.Matcher;
import com.google.inject.spi.DefaultElementVisitor;
import com.google.inject.spi.Element;
import com.google.inject.spi.ElementVisitor;
import com.google.inject.spi.Elements;
import com.google.inject.spi.Message;
import com.google.inject.spi.ModuleWriter;
import com.google.inject.spi.TypeConverter;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.aopalliance.intercept.MethodInterceptor;

/**
 * @author jessewilson@google.com (Jesse Wilson)
 */
public abstract class PrivateModule implements Module {

  private final SourceProvider sourceProvider
      = new SourceProvider().plusSkippedClasses(PrivateModule.class);

  /** When this provider returns, the private injector is ready. */
  private Provider<Ready> readyProvider;

  /** Keys exposed to the public injector */
  private Set<Expose> exposes;

  /** Like abstract module, the binder of the current private module */
  private Binder privateBinder;

  public final synchronized void configure(Binder binder) {
    // when exposes is null, we're being run for the public injector
    if (exposes == null) {
      configurePublicBindings(binder);

    // otherwise we're being run for the private injector
    } else {
      checkState(this.privateBinder == null, "Re-entry is not allowed.");
      privateBinder = binder.skipSources(PrivateModule.class);
      try {
        configurePrivateBindings();

        ProviderMethodsModule providerMethodsModule = ProviderMethodsModule.forPrivateModule(this);
        for (ProviderMethod<?> providerMethod
            : providerMethodsModule.getProviderMethods(privateBinder)) {
          providerMethod.configure(privateBinder);
          if (providerMethod.getMethod().isAnnotationPresent(Exposed.class)) {
            expose(providerMethod.getKey());
          }
        }

        for (Expose<?> expose : exposes) {
          expose.initPrivateProvider(binder);
        }
      } finally {
        privateBinder = null;
      }
    }
  }

  private void configurePublicBindings(Binder publicBinder) {
    exposes = Sets.newLinkedHashSet();
    Key<Ready> readyKey = Key.get(Ready.class, UniqueAnnotations.create());
    readyProvider = publicBinder.getProvider(readyKey);
    try {
      List<Element> privateElements = Elements.getElements(this); // reentrant on configure()
      Set<Key<?>> privatelyBoundKeys = getBoundKeys(privateElements);
      final Module privateModule = new ModuleWriter().create(privateElements);

      for (Expose<?> expose : exposes) {
        if (!privatelyBoundKeys.contains(expose.key)) {
          publicBinder.addError("Could not expose() at %s%n %s must be explicitly bound.", 
              expose.source, expose.key);
        } else {
          expose.configure(publicBinder);
        }
      }

      // create the private injector while the public injector is injecting its members
      publicBinder.bind(readyKey).toProvider(new Provider<Ready>() {
        @Inject Injector publicInjector;
        public Ready get() {
          // this is necessary so the providers from getProvider() will work
          publicInjector.createChildInjector(privateModule);
          return new Ready();
        }
      }).asEagerSingleton();

    } finally {
      readyProvider = null;
      exposes = null;
    }
  }

  private static class Ready {}

  public abstract void configurePrivateBindings();

  protected final <T> void expose(Key<T> key) {
    checkState(exposes != null, "Cannot expose %s, private module is not ready");
    exposes.add(new Expose<T>(sourceProvider.get(), readyProvider, key));
  }

  protected final <T> ExposedKeyBuilder expose(Class<T> type) {
    checkState(exposes != null, "Cannot expose %s, private module is not ready");
    Expose<T> expose = new Expose<T>(sourceProvider.get(), readyProvider, Key.get(type));
    exposes.add(expose);
    return expose;
  }

  protected final <T> ExposedKeyBuilder expose(TypeLiteral<T> type) {
    checkState(exposes != null, "Cannot expose %s, private module is not ready");
    Expose<T> expose = new Expose<T>(sourceProvider.get(), readyProvider, Key.get(type));
    exposes.add(expose);
    return expose;
  }

  public interface ExposedKeyBuilder {
    void annotatedWith(Class<? extends Annotation> annotationType);
    void annotatedWith(Annotation annotation);
  }

  /**
   * A binding from the private injector exposed to the public injector.
   */
  private static class Expose<T> implements ExposedKeyBuilder, Provider<T> {
    private final Object source;
    private final Provider<Ready> readyProvider;
    private Key<T> key; // mutable, a binding annotation may be assigned after Expose creation
    private Provider<T> privateProvider;

    private Expose(Object source, Provider<Ready> readyProvider, Key<T> key) {
      this.source = checkNotNull(source, "source");
      this.readyProvider = checkNotNull(readyProvider, "readyProvider");
      this.key = checkNotNull(key, "key");
    }

    public void annotatedWith(Class<? extends Annotation> annotationType) {
      checkState(key.getAnnotationType() == null, "already annotated");
      key = Key.get(key.getTypeLiteral(), annotationType);
    }

    public void annotatedWith(Annotation annotation) {
      checkState(key.getAnnotationType() == null, "already annotated");
      key = Key.get(key.getTypeLiteral(), annotation);
    }

    /** Sets the provider in the private injector, to be used by the public injector */
    private void initPrivateProvider(Binder privateBinder) {
      privateProvider = privateBinder.withSource(source).getProvider(key);
    }

    /** Creates a binding in the public binder */
    private void configure(Binder publicBinder) {
      publicBinder.withSource(source).bind(key).toProvider(this);
    }

    public T get() {
      readyProvider.get(); // force creation of the private injector
      return privateProvider.get();
    }
  }

  /**
   * Returns the set of keys bound by {@code elements}.
   */
  private Set<Key<?>> getBoundKeys(Iterable<? extends Element> elements) {
    final Set<Key<?>> privatelyBoundKeys = Sets.newHashSet();
    ElementVisitor<Void> visitor = new DefaultElementVisitor<Void>() {
      public <T> Void visitBinding(Binding<T> command) {
        privatelyBoundKeys.add(command.getKey());
        return null;
      }
    };

    for (Element element : elements) {
      element.acceptVisitor(visitor);
    }

    return privatelyBoundKeys;
  }

  // everything below is copied from AbstractModule

  protected final Binder binder() {
    return privateBinder;
  }

  protected final void bindScope(Class<? extends Annotation> scopeAnnotation, Scope scope) {
    privateBinder.bindScope(scopeAnnotation, scope);
  }

  protected final <T> LinkedBindingBuilder<T> bind(Key<T> key) {
    return privateBinder.bind(key);
  }

  protected final <T> AnnotatedBindingBuilder<T> bind(TypeLiteral<T> typeLiteral) {
    return privateBinder.bind(typeLiteral);
  }

  protected final <T> AnnotatedBindingBuilder<T> bind(Class<T> clazz) {
    return privateBinder.bind(clazz);
  }

  protected final AnnotatedConstantBindingBuilder bindConstant() {
    return privateBinder.bindConstant();
  }

  protected final void install(Module module) {
    privateBinder.install(module);
  }

  protected final void addError(String message, Object... arguments) {
    privateBinder.addError(message, arguments);
  }

  protected final void addError(Throwable t) {
    privateBinder.addError(t);
  }

  protected final void addError(Message message) {
    privateBinder.addError(message);
  }

  protected final void requestInjection(Object... objects) {
    privateBinder.requestInjection(objects);
  }

  protected final void requestStaticInjection(Class<?>... types) {
    privateBinder.requestStaticInjection(types);
  }

  protected final void bindInterceptor(Matcher<? super Class<?>> classMatcher,
      Matcher<? super Method> methodMatcher, MethodInterceptor... interceptors) {
    privateBinder.bindInterceptor(classMatcher, methodMatcher, interceptors);
  }

  protected final void requireBinding(Key<?> key) {
    privateBinder.getProvider(key);
  }

  protected final void requireBinding(Class<?> type) {
    privateBinder.getProvider(type);
  }

  protected final <T> Provider<T> getProvider(Key<T> key) {
    return privateBinder.getProvider(key);
  }

  protected final <T> Provider<T> getProvider(Class<T> type) {
    return privateBinder.getProvider(type);
  }

  protected final void convertToTypes(Matcher<? super TypeLiteral<?>> typeMatcher,
      TypeConverter converter) {
    privateBinder.convertToTypes(typeMatcher, converter);
  }

  protected final Stage currentStage() {
    return privateBinder.currentStage();
  }
}
