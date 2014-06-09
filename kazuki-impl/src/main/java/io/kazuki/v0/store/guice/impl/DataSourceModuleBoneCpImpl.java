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
package io.kazuki.v0.store.guice.impl;

import io.kazuki.v0.internal.helper.MaskProxy;
import io.kazuki.v0.internal.helper.ResourceHelper;
import io.kazuki.v0.store.jdbi.JdbiDataSourceConfiguration;
import io.kazuki.v0.store.lifecycle.Lifecycle;
import io.kazuki.v0.store.lifecycle.LifecycleRegistration;
import io.kazuki.v0.store.lifecycle.LifecycleSupportBase;
import io.kazuki.v0.store.management.ComponentDescriptor;
import io.kazuki.v0.store.management.ComponentRegistrar;
import io.kazuki.v0.store.management.KazukiComponent;
import io.kazuki.v0.store.management.impl.ComponentDescriptorImpl;
import io.kazuki.v0.store.management.impl.LateBindingComponentDescriptorImpl;
import io.kazuki.v0.store.sequence.SequenceServiceJdbiImpl;

import javax.inject.Inject;
import javax.sql.DataSource;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.inject.Key;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;
import com.google.inject.Scopes;
import com.google.inject.name.Names;
import com.jolbox.bonecp.BoneCPDataSource;

public class DataSourceModuleBoneCpImpl extends PrivateModule {
  private final String name;
  private final Key<ComponentRegistrar> registrarKey;
  private final Key<Lifecycle> lifecycleKey;
  private final Key<JdbiDataSourceConfiguration> configKey;

  public DataSourceModuleBoneCpImpl(String name, Key<ComponentRegistrar> registrarKey,
      Key<Lifecycle> lifecycleKey, Key<JdbiDataSourceConfiguration> configKey) {
    this.name = name;
    this.registrarKey = registrarKey;
    this.lifecycleKey = lifecycleKey;
    this.configKey = configKey;
  }

  @Override
  protected void configure() {
    // TODO: re-enable ASAP
    // binder().requireExplicitBindings();

    bind(ComponentRegistrar.class).to(registrarKey);
    bind(Lifecycle.class).to(lifecycleKey);

    bind(JdbiDataSourceConfiguration.class).to(configKey);
    Key<DataSource> dsKey = Key.get(DataSource.class, Names.named(name));

    bind(dsKey).toProvider(BoneCPDataSourceProvider.class).in(Scopes.SINGLETON);
    expose(dsKey);
  }

  private static class BoneCPDataSourceProvider
      implements
        Provider<DataSource>,
        LifecycleRegistration,
        KazukiComponent<DataSource> {
    private final JdbiDataSourceConfiguration config;
    private final MaskProxy<DataSource, BoneCPDataSource> instance;
    private final ComponentDescriptor<DataSource> componentDescriptor;
    private volatile Lifecycle lifecycle;

    @Inject
    public BoneCPDataSourceProvider(JdbiDataSourceConfiguration config) {
      this.config = config;
      this.instance = new MaskProxy<DataSource, BoneCPDataSource>(DataSource.class, null);
      this.componentDescriptor =
          new ComponentDescriptorImpl<DataSource>("KZ:DataSource:" + config.getJdbcUrl(),
              DataSource.class, (DataSource) instance.asProxyInstance(),
              new ImmutableList.Builder().add((new LateBindingComponentDescriptorImpl<Lifecycle>() {
                @Override
                public KazukiComponent<Lifecycle> get() {
                  return (KazukiComponent<Lifecycle>) BoneCPDataSourceProvider.this.lifecycle;
                }
              })).build());
    }

    @Override
    public Lifecycle getLifecycle() {
      return this.lifecycle;
    }

    @Override
    @Inject
    public void register(Lifecycle lifecycle) {
      if (this.lifecycle != null && !this.lifecycle.equals(lifecycle)) {
        throw new IllegalStateException("lifecycle already registered with "
            + System.identityHashCode(this.lifecycle));
      }

      this.lifecycle = lifecycle;

      this.lifecycle.register(new LifecycleSupportBase() {
        @Override
        public void init() {
          instance.getAndSet(createDataSource());
        }

        @Override
        public void shutdown() {
          BoneCPDataSource oldInstance = instance.getAndSet(null);

          try {
            oldInstance.close();
          } catch (Exception e) {
            throw Throwables.propagate(e);
          }
        }
      });
    }

    @Override
    public ComponentDescriptor<DataSource> getComponentDescriptor() {
      return this.componentDescriptor;
    }

    @Override
    public void registerAsComponent(ComponentRegistrar manager) {
      manager.register(this.componentDescriptor);
    }

    @Override
    public DataSource get() {
      return instance.asProxyInstance();
    }

    private BoneCPDataSource createDataSource() {
      ResourceHelper.forName(config.getJdbcDriver(), getClass());

      BoneCPDataSource datasource = new BoneCPDataSource();

      datasource.setDriverClass(config.getJdbcDriver());
      datasource.setJdbcUrl(config.getJdbcUrl());
      datasource.setUsername(config.getJdbcUser());
      datasource.setPassword(config.getJdbcPassword());

      datasource.setMinConnectionsPerPartition(config.getPoolMinConnections());
      datasource.setMaxConnectionsPerPartition(config.getPoolMaxConnections());

      return datasource;
    }
  }
}
