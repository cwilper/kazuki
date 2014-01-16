package io.kazuki.v0.store.keyvalue;

import io.kazuki.v0.internal.helper.H2TypeHelper;
import io.kazuki.v0.internal.helper.SqlTypeHelper;
import io.kazuki.v0.store.config.ConfigurationProvider;
import io.kazuki.v0.store.jdbi.IdbiProvider;
import io.kazuki.v0.store.schema.SchemaStoreImpl;
import io.kazuki.v0.store.schema.SchemaStore;
import io.kazuki.v0.store.sequence.SequenceService;

import javax.annotation.Nullable;

import org.skife.jdbi.v2.IDBI;

import com.google.inject.Key;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;
import com.google.inject.name.Names;
import com.jolbox.bonecp.BoneCPDataSource;

public class KeyValueStoreJdbiH2Module extends PrivateModule {
  protected final String name;
  protected final String propertiesPath;

  public KeyValueStoreJdbiH2Module(String name, @Nullable String propertiesPath) {
    this.name = name;
    this.propertiesPath = propertiesPath;
  }

  protected void includeInternal() {}

  protected void includeExposures() {}

  @Override
  public void configure() {
    bind(KeyValueStoreConfiguration.class).toProvider(
        new ConfigurationProvider<KeyValueStoreConfiguration>(name,
            KeyValueStoreConfiguration.class, propertiesPath, true)).asEagerSingleton();

    bind(SqlTypeHelper.class).to(H2TypeHelper.class).asEagerSingleton();

    Provider<SequenceService> seqProvider =
        binder().getProvider(Key.<SequenceService>get(SequenceService.class, Names.named(name)));
    Provider<BoneCPDataSource> dsProvider =
        binder().getProvider(Key.get(BoneCPDataSource.class, Names.named(name)));

    bind(BoneCPDataSource.class).toProvider(dsProvider);
    bind(IDBI.class).toProvider(new IdbiProvider(KeyValueStore.class, dsProvider))
        .asEagerSingleton();

    bind(SequenceService.class).toProvider(seqProvider).asEagerSingleton();

    bind(KeyValueStore.class).to(KeyValueStoreJdbiH2Impl.class).asEagerSingleton();
    bind(Key.get(KeyValueStore.class, Names.named(name))).toProvider(
        binder().getProvider(Key.get(KeyValueStore.class))).asEagerSingleton();

    bind(SchemaStore.class).to(SchemaStoreImpl.class).asEagerSingleton();
    bind(SchemaStore.class).annotatedWith(Names.named(name)).to(Key.get(SchemaStore.class));

    includeInternal();

    expose(Key.get(SchemaStore.class, Names.named(name)));
    expose(Key.get(KeyValueStore.class, Names.named(name)));

    includeExposures();
  }
}
