package io.kazuki.v0.store.easy;

import io.kazuki.v0.store.jdbi.H2DataSourceModule;
import io.kazuki.v0.store.jdbi.JdbiDataSourceConfiguration;
import io.kazuki.v0.store.journal.PartitionedJournalStoreModule;
import io.kazuki.v0.store.keyvalue.KeyValueStoreConfiguration;
import io.kazuki.v0.store.sequence.H2SequenceServiceModule;
import io.kazuki.v0.store.sequence.SequenceServiceConfiguration;

import java.util.concurrent.atomic.AtomicReference;

import com.google.inject.AbstractModule;

public class EasyPartitionedJournalStoreModule extends AbstractModule {
  private final String name;
  private final String propertiesBase;
  private final AtomicReference<JdbiDataSourceConfiguration> jdbiConfig;
  private final AtomicReference<SequenceServiceConfiguration> sequenceConfig;
  private final AtomicReference<KeyValueStoreConfiguration> keyValueConfig;

  public EasyPartitionedJournalStoreModule(String name, String propertiesBase) {
    this.name = name;
    this.propertiesBase = propertiesBase;
    this.jdbiConfig = new AtomicReference<JdbiDataSourceConfiguration>();
    this.sequenceConfig = new AtomicReference<SequenceServiceConfiguration>();
    this.keyValueConfig = new AtomicReference<KeyValueStoreConfiguration>();
  }

  public EasyPartitionedJournalStoreModule withJdbiConfig(JdbiDataSourceConfiguration config) {
    this.jdbiConfig.set(config);

    return this;
  }

  public EasyPartitionedJournalStoreModule withSequenceConfig(SequenceServiceConfiguration config) {
    this.sequenceConfig.set(config);

    return this;
  }

  public EasyPartitionedJournalStoreModule withKeyValueStoreConfig(KeyValueStoreConfiguration config) {
    this.keyValueConfig.set(config);

    return this;
  }

  @Override
  protected void configure() {
    install(new H2DataSourceModule(name, propertiesBase == null ? null : propertiesBase
        + "/jdbi.properties").withConfiguration(this.jdbiConfig.get()));
    install(new H2SequenceServiceModule(name, propertiesBase == null ? null : propertiesBase
        + "/sequence.properties").withConfiguration(this.sequenceConfig.get()));
    install(new PartitionedJournalStoreModule(name, propertiesBase == null ? null : propertiesBase
        + "/keyvalue.properties").withConfiguration(this.keyValueConfig.get()));
  }
}