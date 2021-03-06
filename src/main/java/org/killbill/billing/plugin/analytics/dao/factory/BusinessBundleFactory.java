/*
 * Copyright 2010-2014 Ning, Inc.
 * Copyright 2014 The Billing Project, LLC
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.plugin.analytics.dao.factory;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;

import javax.annotation.Nullable;

import org.joda.time.LocalDate;
import org.killbill.billing.account.api.Account;
import org.killbill.billing.catalog.api.ProductCategory;
import org.killbill.billing.entitlement.api.Subscription;
import org.killbill.billing.entitlement.api.SubscriptionBundle;
import org.killbill.billing.plugin.analytics.AnalyticsRefreshException;
import org.killbill.billing.plugin.analytics.dao.model.BusinessBundleModelDao;
import org.killbill.billing.plugin.analytics.dao.model.BusinessModelDaoBase.ReportGroup;
import org.killbill.billing.plugin.analytics.dao.model.BusinessSubscriptionTransitionModelDao;
import org.killbill.billing.plugin.analytics.utils.CurrencyConverter;
import org.killbill.billing.util.audit.AuditLog;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;

public class BusinessBundleFactory {

    private final Executor executor;

    public BusinessBundleFactory(final Executor executor) {
        this.executor = executor;
    }

    public Collection<BusinessBundleModelDao> createBusinessBundles(final BusinessContextFactory businessContextFactory,
                                                                    // Correctly ordered
                                                                    final Collection<BusinessSubscriptionTransitionModelDao> sortedBsts) throws AnalyticsRefreshException {
        // Pre-fetch these, to avoid contention on BusinessContextFactory
        final Account account = businessContextFactory.getAccount();
        final Long accountRecordId = businessContextFactory.getAccountRecordId();
        final Long tenantRecordId = businessContextFactory.getTenantRecordId();
        final ReportGroup reportGroup = businessContextFactory.getReportGroup();
        final CurrencyConverter currencyConverter = businessContextFactory.getCurrencyConverter();

        // Lookup once all SubscriptionBundle for that account (this avoids expensive lookups for each bundle)
        final Set<UUID> baseSubscriptionIds = new HashSet<UUID>();
        final Map<UUID, SubscriptionBundle> bundles = new LinkedHashMap<UUID, SubscriptionBundle>();
        final Iterable<SubscriptionBundle> bundlesForAccount = businessContextFactory.getAccountBundles();
        for (final SubscriptionBundle bundle : bundlesForAccount) {
            for (final Subscription subscription : bundle.getSubscriptions()) {
                baseSubscriptionIds.add(subscription.getBaseEntitlementId());
                bundles.put(bundle.getId(), bundle);
            }
        }

        final Map<UUID, Integer> rankForBundle = new LinkedHashMap<UUID, Integer>();
        final Map<UUID, BusinessSubscriptionTransitionModelDao> bstForBundle = new LinkedHashMap<UUID, BusinessSubscriptionTransitionModelDao>();
        filterBstsForBasePlans(sortedBsts, baseSubscriptionIds, rankForBundle, bstForBundle);

        // We fetch the bundles in parallel as these can be very large on a per account basis (@see BusinessSubscriptionTransitionFactory)
        final CompletionService<BusinessBundleModelDao> completionService = new ExecutorCompletionService<BusinessBundleModelDao>(executor);
        final Collection<BusinessBundleModelDao> bbss = new LinkedList<BusinessBundleModelDao>();
        for (final BusinessSubscriptionTransitionModelDao bst : bstForBundle.values()) {
            // Fetch audit logs in the main thread as AccountAuditLogs is not thread safe
            final AuditLog creationAuditLog = businessContextFactory.getBundleCreationAuditLog(bst.getBundleId());

            completionService.submit(new Callable<BusinessBundleModelDao>() {
                @Override
                public BusinessBundleModelDao call() throws Exception {
                    return buildBBS(businessContextFactory,
                                    account,
                                    creationAuditLog,
                                    accountRecordId,
                                    bundles,
                                    bst,
                                    rankForBundle.get(bst.getBundleId()),
                                    currencyConverter,
                                    tenantRecordId,
                                    reportGroup
                                   );
                }
            });
        }
        for (final BusinessSubscriptionTransitionModelDao ignored : bstForBundle.values()) {
            try {
                bbss.add(completionService.take().get());
            } catch (InterruptedException e) {
                throw new AnalyticsRefreshException(e);
            } catch (ExecutionException e) {
                throw new AnalyticsRefreshException(e);
            }
        }

        return bbss;
    }

    @VisibleForTesting
    void filterBstsForBasePlans(final Collection<BusinessSubscriptionTransitionModelDao> sortedBundlesBst, final Set<UUID> baseSubscriptionIds, final Map<UUID, Integer> rankForBundle, final Map<UUID, BusinessSubscriptionTransitionModelDao> bstForBundle) {
        UUID lastBundleId = null;
        Integer lastBundleRank = 0;
        for (final BusinessSubscriptionTransitionModelDao bst : sortedBundlesBst) {
            if (!baseSubscriptionIds.contains(bst.getSubscriptionId())) {
                continue;
            }

            // Note that sortedBundlesBst is not ordered bundle by bundle, i.e. we may have:
            // bundleId1 CREATE, bundleId2 CREATE, bundleId1 PHASE, bundleId3 CREATE bundleId2 PHASE
            if (lastBundleId == null || (!lastBundleId.equals(bst.getBundleId()) && rankForBundle.get(bst.getBundleId()) == null)) {
                lastBundleRank++;
                lastBundleId = bst.getBundleId();
                rankForBundle.put(lastBundleId, lastBundleRank);
            }

            // We want the last entry to get the current state
            bstForBundle.put(bst.getBundleId(), bst);
        }
    }

    private BusinessBundleModelDao buildBBS(final BusinessContextFactory businessContextFactory,
                                            final Account account,
                                            final AuditLog creationAuditLog,
                                            final Long accountRecordId,
                                            final Map<UUID, SubscriptionBundle> bundles,
                                            final BusinessSubscriptionTransitionModelDao bst,
                                            final Integer bundleAccountRank,
                                            final CurrencyConverter currencyConverter,
                                            final Long tenantRecordId,
                                            final ReportGroup reportGroup) throws AnalyticsRefreshException {
        final SubscriptionBundle bundle = bundles.get(bst.getBundleId());
        final Long bundleRecordId = businessContextFactory.getBundleRecordId(bundle.getId());
        final Boolean latestForBundleExternalKey = businessContextFactory.getLatestSubscriptionBundleForExternalKey(bundle.getExternalKey()).getId().equals(bundle.getId());

        LocalDate chargedThroughDate = null;
        final Optional<Subscription> base = Iterables.tryFind(bundle.getSubscriptions(),
                                                              new Predicate<Subscription>() {
                                                                  @Override
                                                                  public boolean apply(final Subscription subscription) {
                                                                      return ProductCategory.BASE.equals(subscription.getLastActiveProductCategory());
                                                                  }

                                                                  @Override
                                                                  public boolean test(@Nullable final Subscription input) {
                                                                      return apply(input);
                                                                  }
                                                              }
                                                             );
        if (base.isPresent()) {
            chargedThroughDate = base.get().getChargedThroughDate();
        }

        return new BusinessBundleModelDao(account,
                                          accountRecordId,
                                          bundle,
                                          bundleRecordId,
                                          bundleAccountRank,
                                          latestForBundleExternalKey,
                                          chargedThroughDate,
                                          bst,
                                          currencyConverter,
                                          creationAuditLog,
                                          tenantRecordId,
                                          reportGroup);
    }
}
