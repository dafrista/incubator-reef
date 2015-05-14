/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.reef.runtime.common.driver.evaluator;

import org.apache.reef.annotations.audience.DriverSide;
import org.apache.reef.annotations.audience.Private;
import org.apache.reef.tang.ConfigurationProvider;
import org.apache.reef.driver.context.ContextConfiguration;
import org.apache.reef.driver.evaluator.AllocatedEvaluator;
import org.apache.reef.driver.evaluator.CLRProcessFactory;
import org.apache.reef.driver.evaluator.EvaluatorDescriptor;
import org.apache.reef.driver.evaluator.EvaluatorType;
import org.apache.reef.driver.evaluator.EvaluatorProcess;
import org.apache.reef.driver.evaluator.JVMProcessFactory;
import org.apache.reef.runtime.common.driver.api.ResourceLaunchEventImpl;
import org.apache.reef.runtime.common.evaluator.EvaluatorConfiguration;
import org.apache.reef.runtime.common.files.FileResourceImpl;
import org.apache.reef.runtime.common.files.FileType;
import org.apache.reef.tang.Configuration;
import org.apache.reef.tang.ConfigurationBuilder;
import org.apache.reef.tang.Tang;
import org.apache.reef.tang.exceptions.BindException;
import org.apache.reef.tang.formats.ConfigurationModule;
import org.apache.reef.tang.formats.ConfigurationSerializer;
import org.apache.reef.util.Optional;
import org.apache.reef.util.logging.LoggingScope;
import org.apache.reef.util.logging.LoggingScopeFactory;

import java.io.File;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Driver-Side representation of an allocated evaluator.
 */
@DriverSide
@Private
final class AllocatedEvaluatorImpl implements AllocatedEvaluator {

  private final static Logger LOG = Logger.getLogger(AllocatedEvaluatorImpl.class.getName());

  private final EvaluatorManager evaluatorManager;
  private final String remoteID;
  private final ConfigurationSerializer configurationSerializer;
  private final String jobIdentifier;
  private final LoggingScopeFactory loggingScopeFactory;
  private final Set<ConfigurationProvider> evaluatorConfigurationProviders;
  // TODO: The factories should be removed when deprecated setType is removed, as the process should not be created here
  private final JVMProcessFactory jvmProcessFactory;
  private final CLRProcessFactory clrProcessFactory;

  /**
   * The set of files to be places on the Evaluator.
   */
  private final Collection<File> files = new HashSet<>();
  /**
   * The set of libraries
   */
  private final Collection<File> libraries = new HashSet<>();

  AllocatedEvaluatorImpl(final EvaluatorManager evaluatorManager,
                         final String remoteID,
                         final ConfigurationSerializer configurationSerializer,
                         final String jobIdentifier,
                         final LoggingScopeFactory loggingScopeFactory,
                         final Set<ConfigurationProvider> evaluatorConfigurationProviders,
                         final JVMProcessFactory jvmProcessFactory,
                         final CLRProcessFactory clrProcessFactory) {
    this.evaluatorManager = evaluatorManager;
    this.remoteID = remoteID;
    this.configurationSerializer = configurationSerializer;
    this.jobIdentifier = jobIdentifier;
    this.loggingScopeFactory = loggingScopeFactory;
    this.evaluatorConfigurationProviders = evaluatorConfigurationProviders;
    this.jvmProcessFactory = jvmProcessFactory;
    this.clrProcessFactory = clrProcessFactory;
  }

  @Override
  public String getId() {
    return this.evaluatorManager.getId();
  }

  @Override
  public void close() {
    this.evaluatorManager.close();
  }

  @Override
  public void submitTask(final Configuration taskConfiguration) {
    final Configuration contextConfiguration = ContextConfiguration.CONF
        .set(ContextConfiguration.IDENTIFIER, "RootContext_" + this.getId())
        .build();
    this.submitContextAndTask(contextConfiguration, taskConfiguration);

  }

  @Override
  public EvaluatorDescriptor getEvaluatorDescriptor() {
    return this.evaluatorManager.getEvaluatorDescriptor();
  }


  @Override
  public void submitContext(final Configuration contextConfiguration) {
    launch(contextConfiguration, Optional.<Configuration>empty(), Optional.<Configuration>empty());
  }

  @Override
  public void submitContextAndService(final Configuration contextConfiguration,
                                      final Configuration serviceConfiguration) {
    launch(contextConfiguration, Optional.of(serviceConfiguration), Optional.<Configuration>empty());
  }

  @Override
  public void submitContextAndTask(final Configuration contextConfiguration,
                                   final Configuration taskConfiguration) {
    launch(contextConfiguration, Optional.<Configuration>empty(), Optional.of(taskConfiguration));
  }

  @Override
  public void submitContextAndServiceAndTask(final Configuration contextConfiguration,
                                             final Configuration serviceConfiguration,
                                             final Configuration taskConfiguration) {
    launch(contextConfiguration, Optional.of(serviceConfiguration), Optional.of(taskConfiguration));
  }

  @Override
  @Deprecated
  public void setType(final EvaluatorType type) {
    switch (type) {
      case CLR:
        this.evaluatorManager.setProcess(clrProcessFactory.newEvaluatorProcess());
        break;
      default:
        this.evaluatorManager.setProcess(jvmProcessFactory.newEvaluatorProcess());
        break;
    }
  }

  @Override
  public void setProcess(final EvaluatorProcess process) {
    this.evaluatorManager.setProcess(process);
  }

  @Override
  public void addFile(final File file) {
    this.files.add(file);
  }

  @Override
  public void addLibrary(final File file) {
    this.libraries.add(file);
  }

  private final void launch(final Configuration contextConfiguration,
                            final Optional<Configuration> serviceConfiguration,
                            final Optional<Configuration> taskConfiguration) {
    try (final LoggingScope lb = loggingScopeFactory.evaluatorLaunch(this.getId())) {
      try {
        final Configuration rootContextConfiguration = makeRootContextConfiguration(contextConfiguration);
        final ConfigurationModule evaluatorConfigurationModule = EvaluatorConfiguration.CONF
            .set(EvaluatorConfiguration.APPLICATION_IDENTIFIER, this.jobIdentifier)
            .set(EvaluatorConfiguration.DRIVER_REMOTE_IDENTIFIER, this.remoteID)
            .set(EvaluatorConfiguration.EVALUATOR_IDENTIFIER, this.getId());

        final String encodedContextConfigurationString = this.configurationSerializer.toString(rootContextConfiguration);
        // Add the (optional) service configuration
        final ConfigurationModule contextConfigurationModule;
        if (serviceConfiguration.isPresent()) {
          // With service configuration
          final String encodedServiceConfigurationString = this.configurationSerializer.toString(serviceConfiguration.get());
          contextConfigurationModule = evaluatorConfigurationModule
              .set(EvaluatorConfiguration.ROOT_SERVICE_CONFIGURATION, encodedServiceConfigurationString)
              .set(EvaluatorConfiguration.ROOT_CONTEXT_CONFIGURATION, encodedContextConfigurationString);
        } else {
          // No service configuration
          contextConfigurationModule = evaluatorConfigurationModule
              .set(EvaluatorConfiguration.ROOT_CONTEXT_CONFIGURATION, encodedContextConfigurationString);
        }

        // Add the (optional) task configuration
        final Configuration evaluatorConfiguration;
        if (taskConfiguration.isPresent()) {
          final String encodedTaskConfigurationString = this.configurationSerializer.toString(taskConfiguration.get());
          evaluatorConfiguration = contextConfigurationModule
              .set(EvaluatorConfiguration.TASK_CONFIGURATION, encodedTaskConfigurationString).build();
        } else {
          evaluatorConfiguration = contextConfigurationModule.build();
        }

        final ResourceLaunchEventImpl.Builder rbuilder =
            ResourceLaunchEventImpl.newBuilder()
                .setIdentifier(this.evaluatorManager.getId())
                .setRemoteId(this.remoteID)
                .setEvaluatorConf(evaluatorConfiguration);

        for (final File file : this.files) {
          rbuilder.addFile(FileResourceImpl.newBuilder()
                  .setName(file.getName())
                  .setPath(file.getPath())
                  .setType(FileType.PLAIN)
                  .build());
        }

        for (final File lib : this.libraries) {
          rbuilder.addFile(FileResourceImpl.newBuilder()
                  .setName(lib.getName())
                  .setPath(lib.getPath().toString())
                  .setType(FileType.LIB)
                  .build());
        }

        rbuilder.setProcess(this.evaluatorManager.getEvaluatorDescriptor().getProcess());

        this.evaluatorManager.onResourceLaunch(rbuilder.build());

      } catch (final BindException ex) {
        LOG.log(Level.SEVERE, "Bad Evaluator configuration", ex);
        throw new RuntimeException("Bad Evaluator configuration", ex);
      }
    }
  }

  /**
   * Merges the Configurations provided by the evaluatorConfigurationProviders into the given
   * contextConfiguration.
   *
   * @param contextConfiguration
   * @return
   */
  private Configuration makeRootContextConfiguration(final Configuration contextConfiguration) {

    final EvaluatorType evaluatorType = this.evaluatorManager.getEvaluatorDescriptor().getProcess().getType();
    if (EvaluatorType.JVM != evaluatorType) {
      LOG.log(Level.FINE, "Not using the ConfigurationProviders as we are configuring a {0} Evaluator.", evaluatorType);
      return contextConfiguration;
    }

    final ConfigurationBuilder configurationBuilder = Tang.Factory.getTang()
        .newConfigurationBuilder(contextConfiguration);
    for (final ConfigurationProvider configurationProvider : this.evaluatorConfigurationProviders) {
      configurationBuilder.addConfiguration(configurationProvider.getConfiguration());
    }
    return configurationBuilder.build();
  }

  @Override
  public String toString() {
    return "AllocatedEvaluator{ID='" + getId() + "\'}";
  }
}
