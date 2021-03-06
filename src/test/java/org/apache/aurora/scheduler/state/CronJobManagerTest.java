/**
 * Copyright 2013 Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aurora.scheduler.state;

import java.util.Map;
import java.util.concurrent.Executor;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;

import com.twitter.common.application.ShutdownRegistry;
import com.twitter.common.base.Closure;
import com.twitter.common.base.ExceptionalCommand;
import com.twitter.common.testing.easymock.EasyMockTest;

import org.apache.aurora.gen.AssignedTask;
import org.apache.aurora.gen.ExecutorConfig;
import org.apache.aurora.gen.Identity;
import org.apache.aurora.gen.JobConfiguration;
import org.apache.aurora.gen.ScheduleStatus;
import org.apache.aurora.gen.ScheduledTask;
import org.apache.aurora.gen.TaskConfig;
import org.apache.aurora.scheduler.base.JobKeys;
import org.apache.aurora.scheduler.base.Query;
import org.apache.aurora.scheduler.base.ScheduleException;
import org.apache.aurora.scheduler.configuration.SanitizedConfiguration;
import org.apache.aurora.scheduler.cron.CronScheduler;
import org.apache.aurora.scheduler.events.PubsubEvent;
import org.apache.aurora.scheduler.events.PubsubEvent.StorageStarted;
import org.apache.aurora.scheduler.storage.Storage;
import org.apache.aurora.scheduler.storage.entities.IJobConfiguration;
import org.apache.aurora.scheduler.storage.entities.IScheduledTask;
import org.apache.aurora.scheduler.storage.entities.ITaskConfig;
import org.apache.aurora.scheduler.storage.testing.StorageTestUtil;

import org.easymock.Capture;
import org.easymock.EasyMock;
import org.easymock.IExpectationSetters;

import org.junit.Before;
import org.junit.Test;

import static org.apache.aurora.gen.CronCollisionPolicy.RUN_OVERLAP;
import static org.apache.aurora.gen.apiConstants.DEFAULT_ENVIRONMENT;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;

import static org.junit.Assert.fail;

public class CronJobManagerTest extends EasyMockTest {

  private static final String OWNER = "owner";
  private static final String ENVIRONMENT = "staging11";
  private static final String JOB_NAME = "jobName";
  private static final IScheduledTask TASK = IScheduledTask.build(new ScheduledTask());

  private SchedulerCore scheduler;
  private StateManagerImpl stateManager;
  private Executor delayExecutor;
  private Capture<Runnable> delayLaunchCapture;
  private StorageTestUtil storageUtil;

  private CronScheduler cronScheduler;
  private ShutdownRegistry shutdownRegistry;
  private CronJobManager cron;
  private IJobConfiguration job;
  private SanitizedConfiguration sanitizedConfiguration;

  @Before
  public void setUp() throws Exception {
    scheduler = createMock(SchedulerCore.class);
    stateManager = createMock(StateManagerImpl.class);
    delayExecutor = createMock(Executor.class);
    delayLaunchCapture = createCapture();
    storageUtil = new StorageTestUtil(this);
    storageUtil.expectOperations();
    cronScheduler = createMock(CronScheduler.class);
    shutdownRegistry = createMock(ShutdownRegistry.class);

    cron = new CronJobManager(
        stateManager,
        storageUtil.storage,
        cronScheduler,
        shutdownRegistry,
        delayExecutor);
    cron.schedulerCore = scheduler;
    job = makeJob();
    sanitizedConfiguration = SanitizedConfiguration.fromUnsanitized(job);
  }

  private void expectJobAccepted(IJobConfiguration savedJob) throws Exception {
    storageUtil.jobStore.saveAcceptedJob(CronJobManager.MANAGER_KEY, savedJob);
    expect(cronScheduler.isValidSchedule(savedJob.getCronSchedule())).andReturn(true);
    expect(cronScheduler.schedule(eq(savedJob.getCronSchedule()), EasyMock.<Runnable>anyObject()))
        .andReturn("key");
  }

  private void expectJobAccepted() throws Exception {
    expectJobAccepted(sanitizedConfiguration.getJobConfig());
  }

  private IExpectationSetters<?> expectJobFetch() {
    return expect(storageUtil.jobStore.fetchJob(CronJobManager.MANAGER_KEY, job.getKey()))
        .andReturn(Optional.of(sanitizedConfiguration.getJobConfig()));
  }

  private IExpectationSetters<?> expectActiveTaskFetch(IScheduledTask... activeTasks) {
    return storageUtil.expectTaskFetch(Query.jobScoped(job.getKey()).active(), activeTasks);
  }

  @Test
  public void testPubsubWiring() throws Exception {
    cronScheduler.start();
    shutdownRegistry.addAction(EasyMock.<ExceptionalCommand<?>>anyObject());
    expect(storageUtil.jobStore.fetchJobs(CronJobManager.MANAGER_KEY))
        .andReturn(ImmutableList.<IJobConfiguration>of());

    control.replay();

    Injector injector = Guice.createInjector(new AbstractModule() {
      @Override protected void configure() {
        bind(StateManager.class).toInstance(stateManager);
        bind(Storage.class).toInstance(storageUtil.storage);
        bind(CronScheduler.class).toInstance(cronScheduler);
        bind(ShutdownRegistry.class).toInstance(shutdownRegistry);
        bind(SchedulerCore.class).toInstance(scheduler);
        PubsubTestUtil.installPubsub(binder());
        StateModule.bindCronJobManager(binder());
      }
    });
    cron = injector.getInstance(CronJobManager.class);
    Closure<PubsubEvent> eventSink = PubsubTestUtil.startPubsub(injector);
    eventSink.execute(new StorageStarted());
  }

  @Test
  public void testStart() throws Exception {
    expectJobAccepted();
    expectJobFetch();
    expectActiveTaskFetch();

    // Job is executed immediately since there are no existing tasks to kill.
    stateManager.insertPendingTasks(sanitizedConfiguration.getTaskConfigs());

    control.replay();

    cron.receiveJob(sanitizedConfiguration);
    cron.startJobNow(job.getKey());
  }

  @Test
  public void testDelayedStart() throws Exception {
    expectJobAccepted();
    expectJobFetch();

    // Query to test if live tasks exist for the job.
    expectActiveTaskFetch(TASK);

    // Live tasks exist, so the cron manager must delay the cron launch.
    delayExecutor.execute(capture(delayLaunchCapture));

    // The cron manager will then try to initiate the kill.
    scheduler.killTasks((Query.Builder) anyObject(), eq(CronJobManager.CRON_USER));

    // Immediate query and delayed query.
    expectActiveTaskFetch(TASK).times(2);

    // Simulate the live task disappearing.
    expectActiveTaskFetch();

    stateManager.insertPendingTasks(sanitizedConfiguration.getTaskConfigs());

    control.replay();

    cron.receiveJob(sanitizedConfiguration);
    cron.startJobNow(job.getKey());
    delayLaunchCapture.getValue().run();
  }

  @Test
  public void testDelayedStartResets() throws Exception {
    expectJobAccepted();
    expectJobFetch();

    // Query to test if live tasks exist for the job.
    expectActiveTaskFetch(TASK);

    // Live tasks exist, so the cron manager must delay the cron launch.
    delayExecutor.execute(capture(delayLaunchCapture));

    // The cron manager will then try to initiate the kill.
    scheduler.killTasks((Query.Builder) anyObject(), eq(CronJobManager.CRON_USER));

    // Immediate query and delayed query.
    expectActiveTaskFetch(TASK).times(2);

    // Simulate the live task disappearing.
    expectActiveTaskFetch();

    // Round two.
    expectJobFetch();
    expectActiveTaskFetch(TASK);
    delayExecutor.execute(capture(delayLaunchCapture));
    scheduler.killTasks((Query.Builder) anyObject(), eq(CronJobManager.CRON_USER));
    expectActiveTaskFetch(TASK).times(2);
    expectActiveTaskFetch();

    stateManager.insertPendingTasks(sanitizedConfiguration.getTaskConfigs());
    expectLastCall().times(2);

    control.replay();

    cron.receiveJob(sanitizedConfiguration);
    cron.startJobNow(job.getKey());
    delayLaunchCapture.getValue().run();

    // Start the job again.  Since the previous delayed start completed, this should repeat the
    // entire process.
    cron.startJobNow(job.getKey());
    delayLaunchCapture.getValue().run();
  }

  @Test
  public void testDelayedStartMultiple() throws Exception {
    expectJobAccepted();
    expectJobFetch();

    // Query to test if live tasks exist for the job.
    expectActiveTaskFetch(TASK).times(3);

    // Live tasks exist, so the cron manager must delay the cron launch.
    delayExecutor.execute(capture(delayLaunchCapture));

    // The cron manager will then try to initiate the kill.
    expectJobFetch().times(2);
    scheduler.killTasks((Query.Builder) anyObject(), eq(CronJobManager.CRON_USER));
    expectLastCall().times(3);

    // Immediate queries and delayed query.
    expectActiveTaskFetch(TASK).times(4);

    // Simulate the live task disappearing.
    expectActiveTaskFetch();

    stateManager.insertPendingTasks(sanitizedConfiguration.getTaskConfigs());

    control.replay();

    cron.receiveJob(sanitizedConfiguration);

    // Attempt to trick the cron manager into launching multiple times, or launching multiple
    // pollers.
    cron.startJobNow(job.getKey());
    cron.startJobNow(job.getKey());
    cron.startJobNow(job.getKey());
    delayLaunchCapture.getValue().run();
  }

  @Test
  public void testUpdate() throws Exception {
    SanitizedConfiguration updated = new SanitizedConfiguration(
        IJobConfiguration.build(job.newBuilder().setCronSchedule("1 2 3 4 5")));

    expectJobAccepted();
    cronScheduler.deschedule("key");
    expectJobAccepted(updated.getJobConfig());

    control.replay();

    cron.receiveJob(sanitizedConfiguration);
    cron.updateJob(updated);
  }

  @Test
  public void testRunOverlapNoShardCollision() throws Exception {
    IScheduledTask scheduledTask = IScheduledTask.build(new ScheduledTask()
        .setStatus(ScheduleStatus.RUNNING)
        .setAssignedTask(new AssignedTask().setTask(defaultTask())));
    sanitizedConfiguration = SanitizedConfiguration.fromUnsanitized(
        IJobConfiguration.build(job.newBuilder().setCronCollisionPolicy(RUN_OVERLAP)));
    expectJobAccepted();
    expectJobFetch();
    expectActiveTaskFetch(scheduledTask);

    Map<Integer, ITaskConfig> newConfig =
        ImmutableMap.of(1, sanitizedConfiguration.getJobConfig().getTaskConfig());
    stateManager.insertPendingTasks(newConfig);

    control.replay();

    cron.receiveJob(sanitizedConfiguration);
    cron.startJobNow(job.getKey());
  }

  @Test
  public void testConsistentState() throws Exception {
    IJobConfiguration updated =
        IJobConfiguration.build(makeJob().newBuilder().setCronSchedule("1 2 3 4 5"));

    expectJobAccepted();
    cronScheduler.deschedule("key");
    expectJobAccepted(updated);

    control.replay();

    cron.receiveJob(sanitizedConfiguration);

    IJobConfiguration failedUpdate =
        IJobConfiguration.build(updated.newBuilder().setCronSchedule(null));
    try {
      cron.updateJob(new SanitizedConfiguration(failedUpdate));
      fail();
    } catch (ScheduleException e) {
      // Expected.
    }

    cron.updateJob(new SanitizedConfiguration(updated));
  }

  private IJobConfiguration makeJob() {
    return IJobConfiguration.build(new JobConfiguration()
        .setOwner(new Identity(OWNER, OWNER))
        .setKey(JobKeys.from(OWNER, ENVIRONMENT, JOB_NAME).newBuilder())
        .setCronSchedule("1 1 1 1 1")
        .setTaskConfig(defaultTask())
        .setInstanceCount(1));
  }

  private static TaskConfig defaultTask() {
    return new TaskConfig()
        .setContactEmail("testing@twitter.com")
        .setExecutorConfig(new ExecutorConfig("aurora", "data"))
        .setNumCpus(1)
        .setRamMb(1024)
        .setDiskMb(1024)
        .setJobName(JOB_NAME)
        .setOwner(new Identity(OWNER, OWNER))
        .setEnvironment(DEFAULT_ENVIRONMENT);
  }
}
