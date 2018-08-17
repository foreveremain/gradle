/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.internal;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import groovy.lang.Closure;
import org.gradle.api.Action;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Named;
import org.gradle.api.NamedDomainObjectCollection;
import org.gradle.api.NamedDomainObjectCollectionSchema;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Namer;
import org.gradle.api.Rule;
import org.gradle.api.UnknownDomainObjectException;
import org.gradle.api.internal.collections.CollectionEventRegister;
import org.gradle.api.internal.collections.CollectionFilter;
import org.gradle.api.internal.collections.ElementSource;
import org.gradle.api.internal.plugins.DslObject;
import org.gradle.api.internal.provider.AbstractProvider;
import org.gradle.api.internal.provider.ProviderInternal;
import org.gradle.api.provider.Provider;
import org.gradle.api.reflect.TypeOf;
import org.gradle.api.specs.Spec;
import org.gradle.api.specs.Specs;
import org.gradle.internal.Cast;
import org.gradle.internal.ImmutableActionSet;
import org.gradle.internal.metaobject.AbstractDynamicObject;
import org.gradle.internal.metaobject.DynamicInvokeResult;
import org.gradle.internal.metaobject.DynamicObject;
import org.gradle.internal.metaobject.MethodAccess;
import org.gradle.internal.metaobject.MethodMixIn;
import org.gradle.internal.metaobject.PropertyAccess;
import org.gradle.internal.metaobject.PropertyMixIn;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.util.ConfigureUtil;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

public class DefaultNamedDomainObjectCollection<T> extends DefaultDomainObjectCollection<T> implements NamedDomainObjectCollection<T>, MethodMixIn, PropertyMixIn {

    private final Instantiator instantiator;
    private final Namer<? super T> namer;
    private final Index<T> index;

    private final ContainerElementsDynamicObject elementsDynamicObject = new ContainerElementsDynamicObject();

    private final List<Rule> rules = new ArrayList<Rule>();
    private final Set<String> applyingRulesFor = new HashSet<String>();
    private ImmutableActionSet<ElementInfo<T>> whenKnown = ImmutableActionSet.empty();

    public DefaultNamedDomainObjectCollection(Class<? extends T> type, ElementSource<T> store, Instantiator instantiator, Namer<? super T> namer) {
        super(type, store);
        this.instantiator = instantiator;
        this.namer = namer;
        this.index = new UnfilteredIndex<T>();
        index();
    }

    protected void index() {
        for (T t : getStore()) {
            index.put(namer.determineName(t), t);
        }
    }

    protected DefaultNamedDomainObjectCollection(Class<? extends T> type, ElementSource<T> store, CollectionEventRegister<T> eventRegister, Index<T> index, Instantiator instantiator, Namer<? super T> namer) {
        super(type, store, eventRegister);
        this.instantiator = instantiator;
        this.namer = namer;
        this.index = index;
    }

    // should be protected, but use of the class generator forces it to be public
    public DefaultNamedDomainObjectCollection(DefaultNamedDomainObjectCollection<? super T> collection, CollectionFilter<T> filter, Instantiator instantiator, Namer<? super T> namer) {
        this(filter.getType(), collection.filteredStore(filter), collection.filteredEvents(filter), collection.filteredIndex(filter), instantiator, namer);
    }

    @Override
    public boolean add(final T o) {
        assertMutable("add(T)");
        return add(o, getEventRegister().getAddActions());
    }

    @Override
    protected <I extends T> boolean add(final I o, Action<? super I> notification) {
        final String name = namer.determineName(o);
        if (index.get(name) == null) {
            boolean added = super.add(o, notification);
            if (added) {
                whenKnown.execute(new ObjectBackedElementInfo<T>(name, o));
            }
            return added;
        } else {
            handleAttemptToAddItemWithNonUniqueName(o);
            return false;
        }
    }

    @Override
    protected void realized(ProviderInternal<? extends T> provider) {
        super.realized(provider);
        index.removePending(provider);
    }

    @Override
    public boolean addAll(Collection<? extends T> c) {
        boolean changed = super.addAll(c);
        if (changed) {
            for (T t : c) {
                String name = namer.determineName(t);
                whenKnown.execute(new ObjectBackedElementInfo<T>(name, t));
            }
        }
        return changed;
    }

    @Override
    public void addLater(final Provider<? extends T> provider) {
        assertMutable("addLater(Provider)");
        super.addLater(provider);
        if (provider instanceof Named) {
            final Named named = (Named) provider;
            index.putPending(named.getName(), (ProviderInternal<? extends T>) provider);
            deferredElementKnown(named.getName(), provider);
        }
    }

    public void whenElementKnown(Action<? super ElementInfo<T>> action) {
        whenKnown = whenKnown.add(action);
        Iterator<T> iterator = iteratorNoFlush();
        while (iterator.hasNext()) {
            T next = iterator.next();
            whenKnown.execute(new ObjectBackedElementInfo<T>(namer.determineName(next), next));
        }

        for (Map.Entry<String, ProviderInternal<? extends T>> entry : index.getPendingAsMap().entrySet()) {
            deferredElementKnown(entry.getKey(), entry.getValue());
        }
    }

    protected final void deferredElementKnown(String name, Provider<? extends T> provider) {
        whenKnown.execute(new ProviderBackedElementInfo<T>(name, provider));
    }

    @Override
    protected void didAdd(T toAdd) {
        index.put(namer.determineName(toAdd), toAdd);
    }

    @Override
    public void clear() {
        super.clear();
        index.clear();
    }

    @Override
    protected void didRemove(T t) {
        index.remove(namer.determineName(t));
    }

    @Override
    protected void didRemove(ProviderInternal<? extends T> t) {
        if (t instanceof Named) {
            index.removePending(((Named) t).getName());
        }
        if (t instanceof AbstractDomainObjectCreatingProvider) {
            ((AbstractDomainObjectCreatingProvider) t).removedBeforeRealized = true;
        }
    }

    /**
     * <p>Subclass hook for implementations wanting to throw an exception when an attempt is made to add an item with the same name as an existing item.</p>
     *
     * <p>This implementation does not thrown an exception, meaning that {@code add(T)} will simply return {@code false}.
     *
     * @param o The item that is being attempted to add.
     */
    protected void handleAttemptToAddItemWithNonUniqueName(T o) {
        // do nothing
    }

    /**
     * Asserts that an item with the given name can be added to this collection.
     */
    protected void assertCanAdd(String name) {
        if (hasWithName(name)) {
            throw new InvalidUserDataException(String.format("Cannot add a %s with name '%s' as a %s with that name already exists.", getTypeDisplayName(), name, getTypeDisplayName()));
        }
    }

    /**
     * Asserts that the given item can be added to this collection.
     */
    protected void assertCanAdd(T t) {
        assertCanAdd(getNamer().determineName(t));
    }

    public Namer<T> getNamer() {
        return (Namer) this.namer;
    }

    protected Instantiator getInstantiator() {
        return instantiator;
    }

    protected <S extends T> Index<S> filteredIndex(CollectionFilter<S> filter) {
        return index.filter(filter);
    }

    /**
     * Creates a filtered version of this collection.
     */
    protected <S extends T> DefaultNamedDomainObjectCollection<S> filtered(CollectionFilter<S> filter) {
        return instantiator.newInstance(DefaultNamedDomainObjectCollection.class, this, filter, instantiator, namer);
    }

    public String getDisplayName() {
        return getTypeDisplayName() + " container";
    }

    @Override
    public String toString() {
        return getDisplayName();
    }

    public SortedMap<String, T> getAsMap() {
        return index.asMap();
    }

    public SortedSet<String> getNames() {
        NavigableSet<String> realizedNames = index.asMap().navigableKeySet();
        Set<String> pendingNames = index.getPendingAsMap().keySet();
        if (pendingNames.isEmpty()) {
            return realizedNames;
        }
        TreeSet<String> allNames = new TreeSet<String>(realizedNames);
        allNames.addAll(pendingNames);
        return allNames;
    }

    public <S extends T> NamedDomainObjectCollection<S> withType(Class<S> type) {
        return filtered(createFilter(type));
    }

    public NamedDomainObjectCollection<T> matching(Spec<? super T> spec) {
        return filtered(createFilter(spec));
    }

    public NamedDomainObjectCollection<T> matching(Closure spec) {
        return matching(Specs.<T>convertClosureToSpec(spec));
    }

    public T findByName(String name) {
        T value = findByNameWithoutRules(name);
        if (value != null) {
            return value;
        }
        ProviderInternal<? extends T> provider = index.getPending(name);
        if (provider != null) {
            // TODO - this isn't correct, assumes that a side effect is to add the element
            provider.getOrNull();
            // Use the index here so we can apply any filters to the realized element
            return index.get(name);
        }
        if (!applyRules(name)) {
            return null;
        }
        return findByNameWithoutRules(name);
    }

    protected boolean hasWithName(String name) {
        return index.get(name) != null || index.getPending(name) != null;
    }

    @Nullable
    protected T findByNameWithoutRules(String name) {
        return index.get(name);
    }

    @Nullable
    protected ProviderInternal<? extends T> findByNameLaterWithoutRules(String name) {
        return index.getPending(name);
    }

    protected T removeByName(String name) {
        T it = getByName(name);
        if (it != null) {
            if (remove(it)) {
                return it;
            } else {
                // unclear what the best thing to do here would be
                throw new IllegalStateException(String.format("found '%s' with name '%s' but remove() returned false", it, name));
            }
        } else {
            return null;
        }
    }

    public T getByName(String name) throws UnknownDomainObjectException {
        T t = findByName(name);
        if (t == null) {
            throw createNotFoundException(name);
        }
        return t;
    }

    public T getByName(String name, Closure configureClosure) throws UnknownDomainObjectException {
        T t = getByName(name);
        ConfigureUtil.configure(configureClosure, t);
        return t;
    }

    @Override
    public T getByName(String name, Action<? super T> configureAction) throws UnknownDomainObjectException {
        T t = getByName(name);
        configureAction.execute(t);
        return t;
    }

    public T getAt(String name) throws UnknownDomainObjectException {
        return getByName(name);
    }

    @Override
    public NamedDomainObjectProvider<T> named(String name) throws UnknownDomainObjectException {
        NamedDomainObjectProvider<? extends T> provider = findDomainObject(name);
        if (provider == null) {
            throw createNotFoundException(name);
        }
        return Cast.uncheckedCast(provider);
    }

    @Override
    public MethodAccess getAdditionalMethods() {
        return getElementsAsDynamicObject();
    }

    @Override
    public PropertyAccess getAdditionalProperties() {
        return getElementsAsDynamicObject();
    }

    protected DynamicObject getElementsAsDynamicObject() {
        return elementsDynamicObject;
    }

    @Override
    public NamedDomainObjectCollectionSchema getCollectionSchema() {
        return new NamedDomainObjectCollectionSchema() {
            @Override
            public Iterable<? extends NamedDomainObjectSchema> getElements() {
                return Iterables.concat(
                    Iterables.transform(index.asMap().entrySet(), new Function<Map.Entry<String, T>, NamedDomainObjectSchema>() {
                        @Override
                        public NamedDomainObjectSchema apply(final Map.Entry<String, T> e) {
                            return new NamedDomainObjectSchema() {
                                @Override
                                public String getName() {
                                    return e.getKey();
                                }

                                @Override
                                public TypeOf<?> getPublicType() {
                                    // TODO: This returns the wrong public type for domain objects
                                    // created with the eager APIs or added directly to the container.
                                    // This can leak internal types.
                                    // We do not currently keep track of the type used when creating
                                    // a domain object (via create) or the type of the container when
                                    // a domain object is added directly (via add).
                                    return new DslObject(e.getValue()).getPublicType();
                                }
                            };
                        }
                    }),
                    Iterables.transform(index.getPendingAsMap().entrySet(), new Function<Map.Entry<String, ProviderInternal<? extends T>>, NamedDomainObjectSchema>() {
                        @Override
                        public NamedDomainObjectSchema apply(final Map.Entry<String, ProviderInternal<? extends T>> e) {
                            return new NamedDomainObjectSchema() {
                                @Override
                                public String getName() {
                                    return e.getKey();
                                }

                                @Override
                                public TypeOf<?> getPublicType() {
                                    return TypeOf.typeOf(e.getValue().getType());
                                }
                            };
                        }
                    })
                );
            }
        };
    }

    /**
     * @return true if the method _may_ have done some work
     */
    private boolean applyRules(String name) {
        if (rules.isEmpty() || applyingRulesFor.contains(name)) {
            return false;
        }
        applyingRulesFor.add(name);
        try {
            for (Rule rule : rules) {
                rule.apply(name);
            }
        } finally {
            applyingRulesFor.remove(name);
        }
        return true;
    }

    public Rule addRule(Rule rule) {
        rules.add(rule);
        return rule;
    }

    public Rule addRule(final String description, final Closure ruleAction) {
        return addRule(new RuleAdapter(description) {
            @Override
            public void apply(String domainObjectName) {
                ruleAction.call(domainObjectName);
            }
        });
    }

    @Override
    public Rule addRule(final String description, final Action<String> ruleAction) {
        return addRule(new RuleAdapter(description) {
            @Override
            public void apply(String domainObjectName) {
                ruleAction.execute(domainObjectName);
            }
        });
    }

    private static abstract class RuleAdapter implements Rule {

        private final String description;

        RuleAdapter(String description) {
            this.description = description;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public String toString() {
            return "Rule: " + description;
        }
    }

    public List<Rule> getRules() {
        return Collections.unmodifiableList(rules);
    }

    protected UnknownDomainObjectException createNotFoundException(String name) {
        return new UnknownDomainObjectException(String.format("%s with name '%s' not found.", getTypeDisplayName(),
                name));
    }

    protected String getTypeDisplayName() {
        return getType().getSimpleName();
    }

    private class ContainerElementsDynamicObject extends AbstractDynamicObject {
        @Override
        public String getDisplayName() {
            return DefaultNamedDomainObjectCollection.this.getDisplayName();
        }

        @Override
        public boolean hasProperty(String name) {
            return findByName(name) != null;
        }

        @Override
        public DynamicInvokeResult tryGetProperty(String name) {
            T t = findByName(name);
            return t == null ? DynamicInvokeResult.notFound() : DynamicInvokeResult.found(t);
        }

        @Override
        public Map<String, T> getProperties() {
            return getAsMap();
        }

        @Override
        public boolean hasMethod(String name, Object... arguments) {
            return isConfigureMethod(name, arguments);
        }

        @Override
        public DynamicInvokeResult tryInvokeMethod(String name, Object... arguments) {
            if (isConfigureMethod(name, arguments)) {
                return DynamicInvokeResult.found(ConfigureUtil.configure((Closure) arguments[0], getByName(name)));
            }
            return DynamicInvokeResult.notFound();
        }

        private boolean isConfigureMethod(String name, Object... arguments) {
            return (arguments.length == 1 && arguments[0] instanceof Closure) && hasProperty(name);
        }
    }

    protected interface Index<T> {
        void put(String name, T value);

        @Nullable
        T get(String name);

        void remove(String name);

        void clear();

        NavigableMap<String, T> asMap();

        <S extends T> Index<S> filter(CollectionFilter<S> filter);

        @Nullable
        ProviderInternal<? extends T> getPending(String name);

        void putPending(String name, ProviderInternal<? extends T> provider);

        void removePending(String name);

        void removePending(ProviderInternal<? extends T> provider);

        Map<String, ProviderInternal<? extends T>> getPendingAsMap();
    }

    protected static class UnfilteredIndex<T> implements Index<T> {
        private final Map<String, ProviderInternal<? extends T>> pendingMap = Maps.newLinkedHashMap();
        private final NavigableMap<String, T> map = new TreeMap<String, T>();

        @Override
        public NavigableMap<String, T> asMap() {
            return map;
        }

        @Override
        public void put(String name, T value) {
            map.put(name, value);
        }

        @Override
        public T get(String name) {
            return map.get(name);
        }

        @Override
        public void remove(String name) {
            map.remove(name);
        }

        @Override
        public void clear() {
            map.clear();
            pendingMap.clear();
        }

        @Override
        public <S extends T> Index<S> filter(CollectionFilter<S> filter) {
            return new FilteredIndex<S>(this, filter);
        }

        @Nullable
        @Override
        public ProviderInternal<? extends T> getPending(String name) {
            return pendingMap.get(name);
        }

        @Override
        public void putPending(String name, ProviderInternal<? extends T> provider) {
            pendingMap.put(name, provider);
        }

        @Override
        public void removePending(String name) {
            pendingMap.remove(name);
        }

        @Override
        public void removePending(ProviderInternal<? extends T> provider) {
            pendingMap.values().remove(provider);
        }

        @Override
        public Map<String, ProviderInternal<? extends T>> getPendingAsMap() {
            return pendingMap;
        }
    }

    private static class FilteredIndex<T> implements Index<T> {

        private final Index<? super T> delegate;
        private final CollectionFilter<T> filter;

        FilteredIndex(Index<? super T> delegate, CollectionFilter<T> filter) {
            this.delegate = delegate;
            this.filter = filter;
        }

        @Override
        public void put(String name, T value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public T get(String name) {
            return filter.filter(delegate.get(name));
        }

        @Override
        public void remove(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException();
        }

        @Override
        public NavigableMap<String, T> asMap() {
            NavigableMap<String, ? super T> delegateMap = delegate.asMap();

            NavigableMap<String, T> filtered = new TreeMap<String, T>();
            for (Map.Entry<String, ? super T> entry : delegateMap.entrySet()) {
                T obj = filter.filter(entry.getValue());
                if (obj != null) {
                    filtered.put(entry.getKey(), obj);
                }
            }

            return filtered;
        }

        @Override
        public <S extends T> Index<S> filter(CollectionFilter<S> filter) {
            return new FilteredIndex<S>(delegate, this.filter.and(filter));
        }

        @Nullable
        @Override
        public ProviderInternal<? extends T> getPending(String name) {
            ProviderInternal<?> provider = delegate.getPending(name);
            if (provider != null && provider.getType() != null && filter.getType().isAssignableFrom(provider.getType())) {
                return (ProviderInternal<? extends T>) provider;
            } else {
                return null;
            }
        }

        @Override
        public void putPending(String name, ProviderInternal<? extends T> provider) {
            delegate.putPending(name, provider);
        }

        @Override
        public void removePending(String name) {
            delegate.removePending(name);
        }

        @Override
        public void removePending(ProviderInternal<? extends T> provider) {
            delegate.removePending(provider);
        }

        @Override
        public Map<String, ProviderInternal<? extends T>> getPendingAsMap() {
            // TODO not sure if we can clean up the generics here and do less unchecked casting
            Map<String, ProviderInternal<?>> delegateMap = Cast.uncheckedCast(delegate.getPendingAsMap());
            Map<String, ProviderInternal<? extends T>> filteredMap = Maps.newLinkedHashMap();
            for (Map.Entry<String, ProviderInternal<?>> entry : delegateMap.entrySet()) {
                if (entry.getValue().getType() != null && filter.getType().isAssignableFrom(entry.getValue().getType())) {
                    ProviderInternal<? extends T> typedValue = Cast.uncheckedCast(entry.getValue());
                    filteredMap.put(entry.getKey(), typedValue);
                }
            }
            return filteredMap;
        }
    }

    public interface ElementInfo<T> {
        String getName();

        Class<?> getType();
    }

    private static class ObjectBackedElementInfo<T> implements ElementInfo<T> {
        private final String name;
        private final T o;

        ObjectBackedElementInfo(String name, T o) {
            this.name = name;
            this.o = o;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Class<?> getType() {
            return new DslObject(o).getDeclaredType();
        }
    }

    private static class ProviderBackedElementInfo<T> implements ElementInfo<T> {
        private final String name;
        private final Provider<? extends T> provider;

        ProviderBackedElementInfo(String name, Provider<? extends T> provider) {
            this.name = name;
            this.provider = provider;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Class<?> getType() {
            return ((ProviderInternal<?>) provider).getType();
        }
    }

    @Nullable
    protected NamedDomainObjectProvider<? extends T> findDomainObject(String name) {
        NamedDomainObjectProvider<? extends T> provider = searchForDomainObject(name);
        // Run the rules and try to find something again.
        if (provider == null) {
            if (applyRules(name)) {
                return searchForDomainObject(name);
            }
        }

        return provider;
    }

    @Nullable
    private NamedDomainObjectProvider<? extends T> searchForDomainObject(String name) {
        // Look for a realized object
        T object = findByNameWithoutRules(name);
        if (object != null) {
            return createExistingProvider(name, object);
        }

        // Look for a provider with that name
        ProviderInternal<? extends T> provider = findByNameLaterWithoutRules(name);
        if (provider != null) {
            // TODO: Need to check for proper type/cast
            return Cast.uncheckedCast(provider);
        }

        return null;
    }

    protected NamedDomainObjectProvider<? extends T> createExistingProvider(String name, T object) {
        return Cast.uncheckedCast(getInstantiator().newInstance(ExistingNamedDomainObjectProvider.class, this, name));
    }

    protected abstract class AbstractNamedDomainObjectProvider<I extends T> extends AbstractProvider<I> implements Named, NamedDomainObjectProvider<I> {
        private final String name;
        private final Class<I> type;

        protected AbstractNamedDomainObjectProvider(String name, Class<I> type) {
            this.name = name;
            this.type = type;
        }

        @Nullable
        @Override
        public Class<I> getType() {
            return type;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isPresent() {
            return findDomainObject(getName()) != null;
        }

        @Override
        public String toString() {
            return String.format("provider(%s %s, %s)", getTypeDisplayName(), getName(), getType());
        }
    }

    protected class ExistingNamedDomainObjectProvider<I extends T> extends AbstractNamedDomainObjectProvider<I> {
        public ExistingNamedDomainObjectProvider(String name) {
            super(name, (Class<I>) DefaultNamedDomainObjectCollection.this.getType());
        }

        public void configure(Action<? super I> action) {
            getMutationGuard().withMutationDisabled(action).execute(get());
        }

        @Override
        public boolean isPresent() {
            return getOrNull() != null;
        }

        @Override
        public I get() {
            if (!isPresent()) {
                throw domainObjectRemovedException(getName(), getType());
            }
            return super.get();
        }

        @Override
        public I getOrNull() {
            return Cast.uncheckedCast(findByNameWithoutRules(getName()));
        }
    }

    public abstract class AbstractDomainObjectCreatingProvider<I extends T> extends AbstractNamedDomainObjectProvider<I> {
        private I object;
        private RuntimeException failure;
        protected ImmutableActionSet<I> onCreate;
        private boolean removedBeforeRealized = false;

        public AbstractDomainObjectCreatingProvider(String name, Class<I> type, @Nullable Action<? super I> configureAction) {
            super(name, type);
            this.onCreate = ImmutableActionSet.<I>empty().mergeFrom(getEventRegister().getAddActions());

            if (configureAction != null) {
                configure(configureAction);
            }
        }

        @Override
        public boolean isPresent() {
            return findDomainObject(getName()) != null;
        }

        @Override
        public void configure(final Action<? super I> action) {
            Action<? super I> wrappedAction = withMutationDisabled(action);
            if (object != null) {
                // Already realized, just run the action now
                wrappedAction.execute(object);
                return;
            }
            // Collect any container level add actions then add the object specific action
            onCreate = onCreate.mergeFrom(getEventRegister().getAddActions()).add(wrappedAction);
        }

        protected Action<? super I> withMutationDisabled(Action<? super I> action) {
            return getMutationGuard().withMutationDisabled(action);
        }

        @Override
        public I get() {
            if (wasElementRemoved()) {
                throw domainObjectRemovedException(getName(), getType());
            }
            return super.get();
        }

        @Override
        public I getOrNull() {
            if (wasElementRemoved()) {
                return null;
            }
            if (failure != null) {
                throw failure;
            }
            if (object == null) {
                object = getType().cast(findByNameWithoutRules(getName()));
                if (object == null) {
                    tryCreate();
                }
            }
            return object;
        }

        protected void tryCreate() {
            try {
                // Collect any container level add actions added since the last call to configure()
                onCreate = onCreate.mergeFrom(getEventRegister().getAddActions());

                // Create the domain object
                object = createDomainObject();

                // Register the domain object
                add(object, onCreate);
                realized(AbstractDomainObjectCreatingProvider.this);
                onLazyDomainObjectRealized();
            } catch (Throwable ex) {
                failure = domainObjectCreationException(ex);
                throw failure;
            } finally {
                // Discard state that is no longer required
                onCreate = ImmutableActionSet.empty();
            }
        }

        protected abstract I createDomainObject();

        protected void onLazyDomainObjectRealized() {
            // Do nothing.
        }

        protected boolean wasElementRemoved() {
            // Check for presence as the domain object may have been replaced
            return (wasElementRemovedBeforeRealized() || wasElementRemovedAfterRealized()) && !isPresent();
        }

        private boolean wasElementRemovedBeforeRealized() {
            return removedBeforeRealized;
        }

        private boolean wasElementRemovedAfterRealized() {
            return object != null && findByNameWithoutRules(getName()) == null;
        }

        protected RuntimeException domainObjectCreationException(Throwable cause) {
            return new IllegalStateException(String.format("Could not create domain object '%s' (%s)", getName(), getType().getSimpleName()), cause);
        }
    }

    private static RuntimeException domainObjectRemovedException(String name, Class<?> type) {
        return new IllegalStateException(String.format("The domain object '%s' (%s) for this provider is no longer present in its container.", name, type.getSimpleName()));
    }
}
