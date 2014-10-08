/**
 * Copyright 2012-2014 eBay Software Foundation, All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.ebay.myriad.scheduler.event.handlers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.inject.Inject;

import org.apache.commons.collections.CollectionUtils;
import org.apache.mesos.Protos;
import org.apache.mesos.Protos.Offer;
import org.apache.mesos.Protos.OfferID;
import org.apache.mesos.Protos.Resource;
import org.apache.mesos.Protos.TaskInfo;
import org.apache.mesos.Protos.Value;
import org.apache.mesos.SchedulerDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ebay.myriad.scheduler.NMProfile;
import com.ebay.myriad.scheduler.SchedulerUtils;
import com.ebay.myriad.scheduler.TaskFactory;
import com.ebay.myriad.scheduler.event.ResourceOffersEvent;
import com.ebay.myriad.state.NodeTask;
import com.ebay.myriad.state.SchedulerState;
import com.lmax.disruptor.EventHandler;

public class ResourceOffersEventHandler implements
		EventHandler<ResourceOffersEvent> {
	private static final Logger LOGGER = LoggerFactory
			.getLogger(ResourceOffersEventHandler.class);

	private static final Lock driverOperationLock = new ReentrantLock();
	
	@Inject
	private SchedulerState schedulerState;

	@Inject
	private TaskFactory taskFactory;

	@Override
	public void onEvent(ResourceOffersEvent event, long sequence,
			boolean endOfBatch) throws Exception {
		SchedulerDriver driver = event.getDriver();
		List<Offer> offers = event.getOffers();

		LOGGER.info("Received offers {}", offers.size());
		driverOperationLock.lock();
		try {
			Set<String> pendingTasks = schedulerState.getPendingTaskIds();
			if (CollectionUtils.isNotEmpty(pendingTasks)) {
				for (Offer offer : offers) {
					boolean offerMatch = false;
					for (String pendingTaskId : pendingTasks) {
						NodeTask taskToLaunch = schedulerState
								.getTask(pendingTaskId);
						NMProfile profile = taskToLaunch.getProfile();
						if (matches(offer, profile)
								&& SchedulerUtils.isUniqueHostname(offer,
										schedulerState.getActiveTasks())) {
							LOGGER.info("Offer {} matched profile {}", offer,
									profile);
							TaskInfo task = taskFactory.createTask(offer,
									taskToLaunch);
							List<OfferID> offerIds = new ArrayList<>();
							offerIds.add(offer.getId());
							List<TaskInfo> tasks = new ArrayList<>();
							tasks.add(task);
							LOGGER.info("Launching task: {}", task);
							driver.launchTasks(offerIds, tasks);
							schedulerState.makeTaskStaging(pendingTaskId);
							NodeTask taskLaunched = schedulerState
									.getTask(pendingTaskId);
							taskLaunched.setHostname(offer.getHostname());
							offerMatch = true;
							break;
						}
					}
					if (!offerMatch) {
						LOGGER.info(
								"Declining offer {}, as it didn't match any pending task.",
								offer);
						driver.declineOffer(offer.getId());
					}
				}
			} else {
				LOGGER.info("No pending tasks, declining all offers");
				for (Offer offer : offers) {
					driver.declineOffer(offer.getId());
				}
			}
		} finally {
			driverOperationLock.unlock();
		}
	}

	private boolean matches(Offer offer, NMProfile profile) {
		double cpus = -1;
		double mem = -1;

		for (Resource resource : offer.getResourcesList()) {
			if (resource.getName().equals("cpus")) {
				if (resource.getType().equals(Value.Type.SCALAR)) {
					cpus = resource.getScalar().getValue();
				} else {
					LOGGER.error("Cpus resource was not a scalar: {}", resource
							.getType().toString());
				}
			} else if (resource.getName().equals("mem")) {
				if (resource.getType().equals(Value.Type.SCALAR)) {
					mem = resource.getScalar().getValue();
				} else {
					LOGGER.error("Mem resource was not a scalar: {}", resource
							.getType().toString());
				}
			} else if (resource.getName().equals("disk")) {
				LOGGER.warn("Ignoring disk resources from offer");
			} else if (resource.getName().equals("ports")) {
				LOGGER.info("Ignoring ports resources from offer");
			} else {
				LOGGER.warn("Ignoring unknown resource type: {}",
						resource.getName());
			}
		}

		if (cpus < 0)
			LOGGER.error("No cpus resource present");
		if (mem < 0)
			LOGGER.error("No mem resource present");

		Map<String, String> requestAttributes = new HashMap<String, String>();

		if (profile.getCpus() <= cpus
				&& profile.getMemory() <= mem
				&& SchedulerUtils.isMatchSlaveAttributes(offer,
						requestAttributes)) {
			return true;
		} else {
			LOGGER.info("Offer not sufficient for profile: " + profile);
			return false;
		}
	}

}
