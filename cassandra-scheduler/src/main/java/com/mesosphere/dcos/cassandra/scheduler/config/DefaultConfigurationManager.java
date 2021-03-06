package com.mesosphere.dcos.cassandra.scheduler.config;

import com.mesosphere.dcos.cassandra.common.config.CassandraConfig;
import com.mesosphere.dcos.cassandra.common.config.ClusterTaskConfig;
import com.mesosphere.dcos.cassandra.common.config.ExecutorConfig;
import com.mesosphere.dcos.cassandra.scheduler.offer.PersistentOfferRequirementProvider;
import org.apache.mesos.Protos;
import org.apache.mesos.config.*;
import org.apache.mesos.curator.CuratorConfigStore;
import org.apache.mesos.protobuf.LabelBuilder;
import org.apache.mesos.state.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class DefaultConfigurationManager {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(DefaultConfigurationManager.class);

    private final ConfigStore<Configuration> configStore;
    private final Class<?> configClass;

    private List<ConfigValidationError> validationErrors;
    private StateStore stateStore;

    public DefaultConfigurationManager(
            Class<?> configClass,
            String frameworkName,
            String connectionHost,
            Configuration newConfiguration,
            ConfigValidator configValidator,
            StateStore stateStore) throws ConfigStoreException {
        this.configClass = configClass;
        this.stateStore = stateStore;
        configStore = new CuratorConfigStore<>(frameworkName, connectionHost);
        Configuration oldConfig = null;
        try {
            oldConfig = getTargetConfig();
            LOGGER.info("Current target config: {}", getTargetName());
        } catch (ConfigStoreException e) {
            LOGGER.error("Failed to read existing target config from config store.", e);
        }
        validationErrors = configValidator.validate(oldConfig, newConfiguration);
        LOGGER.error("Validation errors: {}", validationErrors);

        if (validationErrors.isEmpty()) {
            if (!Objects.equals(newConfiguration, oldConfig)) {
                LOGGER.info("Config change detected");
                final UUID uuid = store(newConfiguration);
                LOGGER.info("Stored new configuration with UUID: " + uuid);
                setTargetName(uuid);
                LOGGER.info("Set new configuration target as UUID: " + uuid);
                syncConfigs();
                cleanConfigs();
            } else {
                LOGGER.info("No config change detected.");
            }
        }
    }

    private void cleanConfigs() throws ConfigStoreException {
        Set<UUID> activeConfigs = new HashSet<>();
        activeConfigs.add(getTargetName());
        activeConfigs.addAll(getTaskConfigs());

        LOGGER.info("Cleaning configs which are NOT in the active list: {}", activeConfigs);

        for (UUID configName : getConfigNames()) {
            if (!activeConfigs.contains(configName)) {
                try {
                    LOGGER.info("Removing config: {}", configName);
                    configStore.clear(configName);
                } catch (ConfigStoreException e) {
                    LOGGER.error("Unable to clear config: {} Reason: {}", configName, e);
                }
            }
        }
    }

    public Set<UUID> getTaskConfigs() {
        final Collection<Protos.TaskInfo> taskInfos = stateStore.fetchTasks();
        final Set<UUID> activeConfigs = new HashSet<>();
        try {
            for (Protos.TaskInfo taskInfo : taskInfos) {
                final Protos.Labels labels = taskInfo.getLabels();
                for(Protos.Label label : labels.getLabelsList()) {
                    if (label.getKey().equals(PersistentOfferRequirementProvider.CONFIG_TARGET_KEY)) {
                        activeConfigs.add(UUID.fromString(label.getValue()));
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to fetch configurations from taskInfos.", e);
        }

        return activeConfigs;
    }

    private void syncConfigs() throws ConfigStoreException {
        try {
            final UUID targetConfigName = getTargetName();
            final List<String> duplicateConfigs = getDuplicateConfigs();

            LOGGER.info("Syncing configs. Target: {} Duplicate: {}", targetConfigName.toString(), duplicateConfigs);

            final Collection<Protos.TaskInfo> taskInfos = stateStore.fetchTasks();

            for(Protos.TaskInfo taskInfo : taskInfos) {
                replaceDuplicateConfig(taskInfo, stateStore, duplicateConfigs, targetConfigName);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to sync configurations", e);
            throw new ConfigStoreException(e);
        }
    }

    private void replaceDuplicateConfig(Protos.TaskInfo taskInfo,
                                        StateStore stateStore,
                                        List<String> duplicateConfigs,
                                        UUID targetName) throws ConfigStoreException {
        try {
            final String taskConfigName = getConfigName(taskInfo);
            final String targetConfigName = targetName.toString();

            for(String duplicateConfig : duplicateConfigs) {
                if (duplicateConfig.equals(taskConfigName)) {
                    final Protos.Labels labels = new LabelBuilder()
                            .addLabel(PersistentOfferRequirementProvider.CONFIG_TARGET_KEY, targetConfigName)
                            .build();

                    final Protos.TaskInfo updatedTaskInfo = Protos.TaskInfo.newBuilder(taskInfo)
                            .setLabels(labels).build();
                    stateStore.storeTasks(Arrays.asList(updatedTaskInfo));
                    LOGGER.info("Updated task: {} from duplicate config: {} to current target: {}",
                            updatedTaskInfo.getName(), taskConfigName, targetConfigName);
                    return;
                }
            }
            LOGGER.info("Task: {} is update to date with target config: {}", taskInfo.getName(), targetConfigName);
        } catch (Exception e) {
            LOGGER.error("Failed to replace duplicate config for task: {} Reason: {}", taskInfo, e);
            throw new ConfigStoreException(e);
        }
    }

    public static String getConfigName(Protos.TaskInfo taskInfo) {
        for (Protos.Label label : taskInfo.getLabels().getLabelsList()) {
            if (label.getKey().equals("config_target")) {
                return label.getValue();
            }
        }

        return null;
    }

    private List<String> getDuplicateConfigs() throws ConfigStoreException {
        final CassandraSchedulerConfiguration targetConfig = (CassandraSchedulerConfiguration) getTargetConfig();

        final List<String> duplicateConfigs = new ArrayList<>();
        final CassandraConfig targetCassandraConfig = targetConfig.getCassandraConfig();
        final ClusterTaskConfig targetClusterTaskConfig = targetConfig.getClusterTaskConfig();
        final ExecutorConfig targetExecutorConfig = targetConfig.getExecutorConfig();

        final Collection<UUID> configNames = getConfigNames();
        for (UUID configName : configNames) {
            final CassandraSchedulerConfiguration config = (CassandraSchedulerConfiguration) fetch(configName);
            final CassandraConfig cassandraConfig = config.getCassandraConfig();
            final ClusterTaskConfig clusterTaskConfig = config.getClusterTaskConfig();
            final ExecutorConfig executorConfig = config.getExecutorConfig();

            if (cassandraConfig.equals(targetCassandraConfig) &&
                    clusterTaskConfig.equals(targetClusterTaskConfig) &&
                    executorConfig.equals(targetExecutorConfig)) {
                LOGGER.info("Duplicate config detected: {}", configName);
                duplicateConfigs.add(configName.toString());
            }
        }

        return duplicateConfigs;
    }

    public Configuration fetch(UUID version) throws ConfigStoreException {
        try {
            final ConfigurationFactory<Configuration> yamlConfigurationFactory =
                    new YAMLConfigurationFactory(configClass);
            return configStore.fetch(version, yamlConfigurationFactory);
        } catch (ConfigStoreException e) {
            LOGGER.error("Unable to fetch version: " + version, e);
            throw new ConfigStoreException(e);
        }
    }

    /**
     * Returns whether a current target configuration exists.
     */
    public boolean hasTarget() {
        try {
            return configStore.getTargetConfig() != null;
        } catch (Exception e) {
            LOGGER.error("Failed to determine target config", e);
            return false;
        }
    }

    /**
     * Returns the name of the current target configuration.
     */
    public UUID getTargetName() throws ConfigStoreException {
        try {
            return configStore.getTargetConfig();
        } catch (Exception e) {
            LOGGER.error("Failed to retrieve target config name", e);
            throw e;
        }
    }

    /**
     * Returns the current target configuration.
     *
     * @throws ConfigStoreException if the underlying storage failed to read
     */
    public Configuration getTargetConfig() throws ConfigStoreException {
        return fetch(getTargetName());
    }

    /**
     * Returns a list of all available configuration names.
     *
     * @throws ConfigStoreException if the underlying storage failed to read
     */
    public Collection<UUID> getConfigNames() throws ConfigStoreException {
        return configStore.list();
    }

    /**
     * Stores the provided configuration against the provided version label.
     *
     * @throws ConfigStoreException if the underlying storage failed to write
     */
    public UUID store(Configuration configuration) throws ConfigStoreException {
        try {
            return configStore.store(configuration);
        } catch (Exception e) {
            String msg = "Failure to store configurations.";
            LOGGER.error(msg, e);
            throw new ConfigStoreException(msg, e);
        }
    }

    /**
     * Sets the name of the target configuration to be used in the future.
     */
    public void setTargetName(UUID targetConfigName) throws ConfigStoreException {
        try {
            configStore.setTargetConfig(targetConfigName);
        } catch (Exception ex) {
            String msg = "Failed to set target config with exception";
            LOGGER.error(msg, ex);
            throw new ConfigStoreException(msg, ex);
        }
    }

    public List<ConfigValidationError> getErrors() {
        return validationErrors;
    }
}
