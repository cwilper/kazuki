/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.kazuki.v0.store.keyvalue;


import io.kazuki.v0.internal.helper.Configurations;
import io.kazuki.v0.internal.helper.TestSupport;
import io.kazuki.v0.store.Foo;
import io.kazuki.v0.store.Key;
import io.kazuki.v0.store.guice.KazukiModule;
import io.kazuki.v0.store.keyvalue.KeyValueStoreIteration.SortDirection;
import io.kazuki.v0.store.lifecycle.Lifecycle;
import io.kazuki.v0.store.management.ComponentDescriptor;
import io.kazuki.v0.store.management.KazukiComponent;
import io.kazuki.v0.store.management.KazukiManager;
import io.kazuki.v0.store.schema.SchemaStore;
import io.kazuki.v0.store.schema.TypeValidation;
import io.kazuki.v0.store.schema.model.Schema;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Assert;
import org.testng.annotations.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.name.Names;

public class H2KeyValueStorageTest extends TestSupport {
  private final Injector inject = Guice.createInjector(new KazukiModule.Builder("foo")
      .withJdbiConfiguration("foo", Configurations.getJdbi().build())
      .withSequenceServiceConfiguration("foo", Configurations.getSequence("foo", "foo").build())
      .withKeyValueStoreConfiguration("foo", Configurations.getKeyValue("foo", "foo").build())
      .build());

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  public void testDemo() throws Exception {
    final Lifecycle lifecycle =
        inject.getInstance(com.google.inject.Key.get(Lifecycle.class, Names.named("foo")));

    KeyValueStore store =
        inject.getInstance(com.google.inject.Key.get(KeyValueStore.class, Names.named("foo")));

    SchemaStore manager =
        inject.getInstance(com.google.inject.Key.get(SchemaStore.class, Names.named("foo")));

    lifecycle.init();

    store.clear(false, false);

    lifecycle.stop();
    lifecycle.shutdown();
    lifecycle.init();
    lifecycle.start();

    KazukiManager kzManager =
        inject.getBinding(com.google.inject.Key.get(KazukiManager.class, Names.named("foo")))
            .getProvider().get();

    Map<Class, Object> components = new LinkedHashMap<Class, Object>();

    for (ComponentDescriptor desc : kzManager.getComponents()) {
      components.put(desc.getClazz(), desc.getInstance());
    }

    Assert.assertEquals(components.get(Lifecycle.class), ((KazukiComponent) lifecycle)
        .getComponentDescriptor().getInstance());
    Assert.assertEquals(components.get(KeyValueStore.class), ((KazukiComponent) store)
        .getComponentDescriptor().getInstance());
    Assert.assertEquals(components.get(SchemaStore.class), ((KazukiComponent) manager)
        .getComponentDescriptor().getInstance());

    Assert.assertFalse(store.iterators().iterator("$schema", Schema.class, SortDirection.ASCENDING)
        .hasNext());

    manager.createSchema("foo", Foo.FOO_SCHEMA);

    try (KeyValueIterator<Schema> sIter =
        store.iterators().iterator("$schema", Schema.class, SortDirection.ASCENDING)) {
      Assert.assertTrue(sIter.hasNext());
      sIter.next();
      Assert.assertFalse(sIter.hasNext());
    }

    KeyValuePair<Foo> foo1KeyValuePair =
        store.create("foo", Foo.class, new Foo("k", "v"), TypeValidation.STRICT);
    Key foo1Key = foo1KeyValuePair.getKey();
    log.info("created key = " + foo1Key);
    Assert.assertNotNull(store.retrieve(foo1Key, Foo.class));

    KeyValuePair<Foo> foo2KeyValuePair =
        store.create("foo", Foo.class, new Foo("a", "b"), TypeValidation.STRICT);
    Key foo2Key = foo2KeyValuePair.getKey();
    log.info("created key = " + foo2Key);
    Assert.assertNotNull(store.retrieve(foo2Key, Foo.class));

    try (KeyValueIterator<Foo> iter =
        store.iterators().iterator("foo", Foo.class, SortDirection.ASCENDING)) {
      Assert.assertTrue(iter.hasNext());
      while (iter.hasNext()) {
        Foo theNext = iter.next();
        Assert.assertNotNull(theNext);
        log.info("dump all : " + dump(theNext));
      }
    }

    Foo foo1Found = store.retrieve(foo1Key, Foo.class);
    log.info("retrieved value 1 = " + dump(foo1Found));
    Foo foo2Found = store.retrieve(foo2Key, Foo.class);
    log.info("retrieved value 2 = " + dump(foo2Found));

    Map<Key, Foo> multiFound = store.multiRetrieve(ImmutableList.of(foo1Key, foo2Key), Foo.class);
    log.info("multi-retrieved values = " + dump(multiFound));
    Assert.assertEquals(multiFound.size(), 2);
    Assert.assertEquals(multiFound.get(foo1Key), foo1Found);
    Assert.assertEquals(multiFound.get(foo2Key), foo2Found);

    boolean updated = store.update(foo1Key, Foo.class, new Foo("x", "y"));
    log.info("updated? " + updated);
    Assert.assertTrue(updated);

    Foo foo1FoundAgain = store.retrieve(foo1Key, Foo.class);
    log.info("retrieved value = " + dump(foo1FoundAgain));
    Assert.assertNotSame(foo1FoundAgain, foo1Found);

    boolean deleted = store.delete(foo1Key);
    log.info("deleted? " + deleted);
    Assert.assertTrue(deleted);

    foo1Found = store.retrieve(foo1Key, Foo.class);
    log.info("retrieved value = " + dump(foo1Found));
    Assert.assertNull(foo1Found);

    lifecycle.stop();
    lifecycle.shutdown();
    lifecycle.init();
    lifecycle.start();

    foo1Found = store.retrieve(foo1Key, Foo.class);
    log.info("retrieved value = " + dump(foo1Found));
    Assert.assertNull(foo1Found);

    foo1Found = store.retrieve(foo2Key, Foo.class);
    log.info("retrieved value = " + dump(foo2Found));
    Assert.assertNotNull(foo2Found);

    store.clear(false, false);
  }

  public String dump(Object object) throws Exception {
    return mapper.writeValueAsString(object);
  }
}
