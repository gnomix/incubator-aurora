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
package org.apache.aurora.scheduler.quota;

import javax.inject.Inject;

import com.google.common.collect.Iterables;

import org.apache.aurora.scheduler.base.JobKeys;
import org.apache.aurora.scheduler.base.Query;
import org.apache.aurora.scheduler.base.Tasks;
import org.apache.aurora.scheduler.quota.QuotaManager.QuotaManagerImpl;
import org.apache.aurora.scheduler.state.JobFilter;
import org.apache.aurora.scheduler.storage.Storage;
import org.apache.aurora.scheduler.storage.entities.IJobConfiguration;
import org.apache.aurora.scheduler.storage.entities.IJobKey;
import org.apache.aurora.scheduler.storage.entities.IQuota;
import org.apache.aurora.scheduler.storage.entities.ITaskConfig;

import static com.google.common.base.Preconditions.checkNotNull;

import static org.apache.aurora.scheduler.quota.QuotaComparisonResult.Result.INSUFFICIENT_QUOTA;

/**
 * A filter that fails production jobs for roles that do not have sufficient quota to run them.
 */
class QuotaFilter implements JobFilter {
  private final QuotaManagerImpl quotaManager;
  private final Storage storage;

  @Inject
  QuotaFilter(QuotaManagerImpl quotaManager, Storage storage) {
    this.quotaManager = checkNotNull(quotaManager);
    this.storage = checkNotNull(storage);
  }

  @Override
  public JobFilterResult filter(final IJobConfiguration job) {
    return filterByTask(job.getKey(), job.getTaskConfig(), job.getInstanceCount());
  }

  @Override
  public JobFilterResult filter(ITaskConfig template, int instanceCount) {
    return filterByTask(JobKeys.from(template), template, instanceCount);
  }

  private synchronized JobFilterResult filterByTask(
      IJobKey jobKey,
      ITaskConfig template,
      int instanceCount) {

    if (!template.isProduction()) {
      return JobFilterResult.pass();
    }

    IQuota currentUsage = Quotas.fromProductionTasks(
        Iterables.transform(
            Storage.Util.consistentFetchTasks(storage, Query.jobScoped(jobKey).active()),
            Tasks.SCHEDULED_TO_INFO));

    IQuota additionalRequested =
        Quotas.subtract(Quotas.fromTasks(template, instanceCount), currentUsage);
    QuotaComparisonResult comparisonResult =
        quotaManager.checkQuota(jobKey.getRole(), additionalRequested);

    if (comparisonResult.result() == INSUFFICIENT_QUOTA) {
      return JobFilterResult.fail("Insufficient resource quota: " + comparisonResult.details());
    }

    return JobFilterResult.pass();
  }
}
