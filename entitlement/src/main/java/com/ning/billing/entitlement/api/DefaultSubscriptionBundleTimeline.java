/*
 * Copyright 2010-2013 Ning, Inc.
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

package com.ning.billing.entitlement.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.LocalDate;

import com.ning.billing.catalog.api.BillingPeriod;
import com.ning.billing.catalog.api.Plan;
import com.ning.billing.catalog.api.PlanPhase;
import com.ning.billing.catalog.api.PriceList;
import com.ning.billing.catalog.api.Product;
import com.ning.billing.entitlement.DefaultEntitlementService;
import com.ning.billing.entitlement.block.BlockingChecker.BlockingAggregator;
import com.ning.billing.entitlement.block.DefaultBlockingChecker.DefaultBlockingAggregator;
import com.ning.billing.subscription.api.SubscriptionBase;
import com.ning.billing.subscription.api.SubscriptionBaseTransitionType;
import com.ning.billing.subscription.api.user.SubscriptionBaseTransition;
import com.ning.billing.util.svcapi.junction.DefaultBlockingState;

import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;

public class DefaultSubscriptionBundleTimeline implements SubscriptionBundleTimeline {

    private final List<SubscriptionEvent> events;
    private final UUID accountId;
    private final UUID bundleId;
    private final String externalKey;


    public DefaultSubscriptionBundleTimeline(final DateTimeZone accountTimeZone, final UUID accountId, final UUID bundleId, final String externalKey, final List<Entitlement> entitlements, List<BlockingState> allBlockingStates) {
        this.accountId = accountId;
        this.bundleId = bundleId;
        this.externalKey = externalKey;
        this.events = computeEvents(entitlements, allBlockingStates, accountTimeZone);
    }

    //
    // Compute all events based on blocking states events and base subscription events
    // Note that:
    // - base subscription events are already ordered for each Entitlement and so when we reorder at the bundle level we try not to break that initial ordering
    // - blocking state events occur at various level (account, bundle and subscription) so for higher level, we need to dispatch that on each subscription.
    //
    private List<SubscriptionEvent> computeEvents(final List<Entitlement> entitlements, List<BlockingState> allBlockingStates, final DateTimeZone accountTimeZone) {

        // Extract ids for all entitlement in the list
        final Set<UUID> allEntitlementUUIDs = new TreeSet(Collections2.transform(entitlements, new Function<Entitlement, UUID>() {
            @Override
            public UUID apply(final Entitlement input) {
                return input.getId();
            }
        }));

        // Compute base events across all entitlements
        final LinkedList<SubscriptionEvent> result = computeSubscriptionBaseEvents(entitlements, accountTimeZone);

        // Order allBlockingStates  events by effectiveDate, createdDate, uuid, service, serviceState
        Collections.sort(allBlockingStates, new Comparator<BlockingState>() {
            @Override
            public int compare(final BlockingState o1, final BlockingState o2) {
                final int effectivedComp = o1.getEffectiveDate().compareTo(o2.getEffectiveDate());
                if (effectivedComp != 0) {
                    return effectivedComp;
                }
                final int createdDateComp = o1.getCreatedDate().compareTo(o2.getCreatedDate());
                if (createdDateComp != 0) {
                    return createdDateComp;
                }
                final int uuidComp = o1.getId().compareTo(o2.getId());
                if (uuidComp != 0) {
                    return uuidComp;
                }
                // Same effectiveDate, createdDate and for the same object, we sort first by serviceName and then serviceState
                final int serviceNameComp = o1.getService().compareTo(o2.getService());
                if (serviceNameComp != 0) {
                    return serviceNameComp;
                }
                final int serviceStateComp = o1.getStateName().compareTo(o2.getStateName());
                if (serviceStateComp != 0) {
                    return serviceStateComp;
                }
                // Underministic-- not sure that will ever happen.
                return 0;
            }
        });

        for (BlockingState bs : allBlockingStates) {
            final LocalDate bsEffectiveDate = new LocalDate(bs.getEffectiveDate(), accountTimeZone);


            final List<SubscriptionEvent> newEvents = new ArrayList<SubscriptionEvent>();
            int index = insertFromBlockingEvent(accountTimeZone, allEntitlementUUIDs, result, bs, bsEffectiveDate, newEvents);
            result.addAll(index, newEvents);
        }
        return result;
    }

    private int insertFromBlockingEvent(final DateTimeZone accountTimeZone, final Set<UUID> allEntitlementUUIDs, final LinkedList<SubscriptionEvent> result, final BlockingState bs, final LocalDate bsEffectiveDate, final List<SubscriptionEvent> newEvents) {


        // Keep the current state per entitlement
        final Map<UUID, TargetState> targetStates= new HashMap<UUID, TargetState>();
        for (UUID cur : allEntitlementUUIDs) {
            targetStates.put(cur, new TargetState());
        }

        //
        // Find out where to insert next event, and calculate current state for each entitlement at the position where we stop.
        //
        int index = -1;
        final Iterator<SubscriptionEvent> it = result.iterator();
        // Where we need to insert in that stream
        DefaultSubscriptionEvent cur = null;
        while (it.hasNext()) {
            cur = (DefaultSubscriptionEvent) it.next();
            index++;

            final int compEffectiveDate = bsEffectiveDate.compareTo(cur.getEffectiveDate());
            final boolean shouldContinue = (compEffectiveDate > 0 ||
                                            (compEffectiveDate == 0 && bs.getCreatedDate().compareTo(cur.getCreatedDate()) >= 0));

            final TargetState curTargetState = targetStates.get(cur.getEntitlementId());
            if (shouldContinue) {
                switch (cur.getSubscriptionEventType()) {
                    case START_ENTITLEMENT:
                        curTargetState.setEntitlementStarted();
                        break;
                    case STOP_ENTITLEMENT:
                        curTargetState.setEntitlementStopped();
                        break;
                    case START_BILLING:
                        curTargetState.setBillingStarted();
                        break;
                    case PAUSE_BILLING:
                    case PAUSE_ENTITLEMENT:
                        curTargetState.addEntitlementEvent(cur);
                        break;
                    case STOP_BILLING:
                        curTargetState.setBillingStopped();
                        break;
                }
            } else {
                break;
            }
        }

        // Extract the list of targets based on the type of blocking state
        final List<UUID> targetEntitlementIds = bs.getType() == BlockingStateType.SUBSCRIPTION ? ImmutableList.<UUID>of(bs.getBlockedId()) :
                                                ImmutableList.<UUID>copyOf(allEntitlementUUIDs);

        // For each target compute the new events that should be inserted in the stream
        for (UUID target : targetEntitlementIds) {

            final SubscriptionEvent[] prevNext = findPrevNext(result, target, cur, bs);
            final TargetState curTargetState = targetStates.get(target);

            final List<SubscriptionEventType> eventTypes = curTargetState.addStateAndReturnEventTypes(bs);
            for (SubscriptionEventType t : eventTypes) {
                newEvents.add(toSubscriptionEvent(prevNext[0], prevNext[1], target, bs, t, accountTimeZone));
            }
        }
        return index;
    }

    private SubscriptionEvent[] findPrevNext(final List<SubscriptionEvent> events, final UUID targetEntitlementId, final SubscriptionEvent insertionEvent, final BlockingState bs) {

        // Find prev/next event for the same entitlement
        final SubscriptionEvent[] result =  new DefaultSubscriptionEvent[2];
        final Iterator<SubscriptionEvent> it = events.iterator();
        DefaultSubscriptionEvent prev = null;
        DefaultSubscriptionEvent next = null;
        boolean foundCur = false;
        while (it.hasNext()) {
            final DefaultSubscriptionEvent tmp = (DefaultSubscriptionEvent) it.next();
            if (tmp.getEntitlementId().equals(targetEntitlementId)) {
                if (!foundCur) {
                    prev = tmp;
                } else {
                    next = tmp;
                    break;
                }
            }
            if (tmp.getId().equals(insertionEvent.getId())) {
                foundCur = true;
            }
        }
        result[0] = prev;
        result[1] = next;
        return result;
    }


    private LinkedList<SubscriptionEvent> computeSubscriptionBaseEvents(final List<Entitlement> entitlements, final DateTimeZone accountTimeZone) {

        final LinkedList<SubscriptionEvent> result = new LinkedList<SubscriptionEvent>();
        for (Entitlement cur : entitlements) {
            final SubscriptionBase base = ((DefaultEntitlement) cur).getSubscriptionBase();
            final List<SubscriptionBaseTransition> baseTransitions = base.getAllTransitions();
            for (SubscriptionBaseTransition tr : baseTransitions) {
                final SubscriptionEventType eventType = toEventType(tr.getTransitionType());
                if (eventType == null) {
                    continue;
                }
                final SubscriptionEvent event = toSubscriptionEvent(tr, eventType, accountTimeZone);
                insertSubscriptionEvent(event, result);
                if (tr.getTransitionType() == SubscriptionBaseTransitionType.CREATE ||
                    tr.getTransitionType() == SubscriptionBaseTransitionType.TRANSFER) {
                    final SubscriptionEvent billingEvent = toSubscriptionEvent(tr, SubscriptionEventType.START_BILLING, accountTimeZone);
                    insertSubscriptionEvent(billingEvent, result);
                }
            }
        }
        sanitizeForBaseRecreateEvents(result);
        return result;
    }


    //
    // Old version of code would use CANCEL/ RE_CREATE to simulate PAUSE_BILLING/RESUME_BILLING
    //
    private void sanitizeForBaseRecreateEvents(final LinkedList<SubscriptionEvent> input) {

        final Set<UUID> guiltyEntitlementIds = new TreeSet<UUID>();
        ListIterator<SubscriptionEvent> it = input.listIterator(input.size() - 1);
        while (it.hasPrevious()) {
            final SubscriptionEvent cur = it.previous();
            if (cur.getSubscriptionEventType() == SubscriptionEventType.RESUME_BILLING) {
                guiltyEntitlementIds.add(cur.getId());
                continue;
            }
            if (cur.getSubscriptionEventType() == SubscriptionEventType.STOP_BILLING &&
                guiltyEntitlementIds.contains(cur.getId())) {
                guiltyEntitlementIds.remove(cur.getId());
                final SubscriptionEvent correctedEvent = new DefaultSubscriptionEvent((DefaultSubscriptionEvent) cur, SubscriptionEventType.PAUSE_BILLING);
                it.set(correctedEvent);
            }
        }
    }


    private void insertSubscriptionEvent(final SubscriptionEvent event, final LinkedList<SubscriptionEvent> result) {
        int index = 0;
        for (SubscriptionEvent cur : result) {
            int compEffectiveDate = event.getEffectiveDate().compareTo(cur.getEffectiveDate());
            if (compEffectiveDate < 0) {
                // EffectiveDate is less than cur -> insert here
                break;
            } else if (compEffectiveDate == 0) {

                int compCreatedDate = ((DefaultSubscriptionEvent) event).getCreatedDate().compareTo(((DefaultSubscriptionEvent) cur).getCreatedDate());
                if (compCreatedDate < 0) {
                    // Same EffectiveDate but CreatedDate is less than cur -> insert here
                    break;
                } else if (compCreatedDate == 0) {
                    int compUUID = event.getId().compareTo(cur.getId());
                    if (compUUID < 0) {
                        // Same EffectiveDate and CreatedDate but order by ID
                        break;
                    } else if (compUUID == 0) {
                        if (event.getSubscriptionEventType().ordinal() < cur.getSubscriptionEventType().ordinal()) {
                            // Same EffectiveDate, CreatedDate and ID, but event type is lower -- as described in enum
                            break;
                        }
                    }
                }
            }
            index++;
        }
        result.add(index, event);
    }

    private SubscriptionEvent toSubscriptionEvent(final SubscriptionEvent prev, final SubscriptionEvent next, final UUID entitlementId, final BlockingState in, final SubscriptionEventType eventType, final DateTimeZone accountTimeZone) {
        return new DefaultSubscriptionEvent(in.getId(),
                                            entitlementId,
                                            in.getEffectiveDate(),
                                            in.getCreatedDate(),
                                            eventType,
                                            in.isBlockEntitlement(),
                                            in.isBlockBilling(),
                                            in.getService(),
                                            in.getStateName(),
                                            // We look for the next for the 'prev' meaning we we are headed to, but if this is null -- for example on cancellation we get the prev which gives the correct state.
                                            prev != null ? (prev.getNextProduct() != null ? prev.getNextProduct() : prev.getPrevProduct()) : null,
                                            prev != null ? (prev.getNextPlan() != null ? prev.getNextPlan() : prev.getPrevPlan()) : null,
                                            prev != null ? (prev.getNextPhase() != null ? prev.getNextPhase() : prev.getPrevPhase()) : null,
                                            prev != null ? (prev.getNextPriceList() != null ? prev.getNextPriceList() : prev.getPrevPriceList()) : null,
                                            prev != null ? (prev.getNextBillingPeriod() != null ? prev.getNextBillingPeriod() : prev.getPrevBillingPeriod())  : null,
                                            next != null ? next.getPrevProduct() : null,
                                            next != null ? next.getPrevPlan() : null,
                                            next != null ? next.getPrevPhase() : null,
                                            next != null ? next.getPrevPriceList() : null,
                                            next != null ? next.getPrevBillingPeriod() : null,
                                            in.getCreatedDate(),
                                            accountTimeZone);
    }

    private SubscriptionEvent toSubscriptionEvent(final SubscriptionBaseTransition in, final SubscriptionEventType eventType, final DateTimeZone accountTimeZone) {
        return new DefaultSubscriptionEvent(in.getId(),
                                            in.getSubscriptionId(),
                                            in.getEffectiveTransitionTime(),
                                            in.getRequestedTransitionTime(),
                                            eventType,
                                            false,
                                            false,
                                            DefaultEntitlementService.ENTITLEMENT_SERVICE_NAME,
                                            eventType.toString(),
                                            (in.getPreviousPlan() != null ? in.getPreviousPlan().getProduct() : null),
                                            in.getPreviousPlan(),
                                            in.getPreviousPhase(),
                                            in.getPreviousPriceList(),
                                            (in.getPreviousPlan() != null ? in.getPreviousPlan().getBillingPeriod() : null),
                                            (in.getNextPlan() != null ? in.getNextPlan().getProduct() : null),
                                            in.getNextPlan(),
                                            in.getNextPhase(),
                                            in.getNextPriceList(),
                                            (in.getNextPlan() != null ? in.getNextPlan().getBillingPeriod() : null),
                                            in.getCreatedDate(),
                                            accountTimeZone);
    }

    private SubscriptionEventType toEventType(final SubscriptionBaseTransitionType in) {
        switch (in) {
            case CREATE:
                return SubscriptionEventType.START_ENTITLEMENT;
            case MIGRATE_ENTITLEMENT:
                return SubscriptionEventType.START_ENTITLEMENT;
            case TRANSFER:
                return SubscriptionEventType.START_ENTITLEMENT;
            case MIGRATE_BILLING:
                return SubscriptionEventType.START_BILLING;
            case CHANGE:
                return SubscriptionEventType.CHANGE;
            case CANCEL:
                return SubscriptionEventType.STOP_BILLING;
            case PHASE:
                return SubscriptionEventType.PHASE;
            // STEPH This is the old way of pausing billing; not used any longer, but kept for compatibility reason
            case RE_CREATE:
                return SubscriptionEventType.RESUME_BILLING;
            /*
             * Those can be ignored:
             */
            // Marker event
            case UNCANCEL:
                // Junction billing events-- that info is part of blocking states, we will get outside of subscription base
            case START_BILLING_DISABLED:
            case END_BILLING_DISABLED:
            default:
                return null;
        }
    }


    @Override
    public UUID getAccountId() {
        return accountId;
    }

    @Override
    public UUID getBundleId() {
        return bundleId;
    }

    @Override
    public String getExternalKey() {
        return externalKey;
    }

    @Override
    public List<SubscriptionEvent> getSubscriptionEvents() {
        return events;
    }

    private final static class TargetState {

        private boolean isEntitlementStarted;
        private boolean isEntitlementStopped;
        private boolean isBillingStarted;
        private boolean isBillingStopped;
        private Map<String, BlockingState> perServiceBlockingState;

        public TargetState() {
            this.isEntitlementStarted = false;
            this.isEntitlementStopped = false;
            this.isBillingStarted = false;
            this.isBillingStopped = false;
            this.perServiceBlockingState = new HashMap<String, BlockingState>();
        }

        public void setEntitlementStarted() {
            isEntitlementStarted = true;
        }

        public void setEntitlementStopped() {
            isEntitlementStopped = true;
        }


        public void setBillingStarted() {
            isBillingStarted = true;
        }

        public void setBillingStopped() {
            isBillingStopped = true;
        }

        public void addEntitlementEvent(final SubscriptionEvent e) {
            final BlockingState converted = new DefaultBlockingState(e.getEntitlementId(), BlockingStateType.SUBSCRIPTION,
                                                                     e.getServiceStateName(), e.getServiceName(), false, e.isBlockedEntitlement(), e.isBlockedBilling(),
                                                                     ((DefaultSubscriptionEvent) e).getEffectiveDateTime());
            perServiceBlockingState.put(converted.getService(), converted);

        }
        public List<SubscriptionEventType> addStateAndReturnEventTypes(final BlockingState bs) {

            final List<SubscriptionEventType> result  = new ArrayList<SubscriptionEventType>(4);
            if (bs.getStateName().equals(DefaultEntitlementApi.ENT_STATE_CANCELLED)) {
                isEntitlementStopped = true;
                result.add(SubscriptionEventType.STOP_ENTITLEMENT);
                return result;
            }

            final BlockingAggregator stateBefore = getState();
            perServiceBlockingState.put(bs.getService(), bs);
            final BlockingAggregator stateAfter = getState();

            final boolean shouldResumeEntitlement = isEntitlementStarted && !isEntitlementStopped && stateBefore.isBlockEntitlement() && !stateAfter.isBlockEntitlement();
            if (shouldResumeEntitlement) {
                result.add(SubscriptionEventType.RESUME_ENTITLEMENT);
            }
            final boolean shouldResumeBilling = isBillingStarted && !isBillingStopped && stateBefore.isBlockBilling() && !stateAfter.isBlockBilling();
            if (shouldResumeBilling) {
                result.add(SubscriptionEventType.RESUME_BILLING);
            }

            final boolean shouldBlockEntitlement = isEntitlementStarted && !isEntitlementStopped && !stateBefore.isBlockEntitlement() && stateAfter.isBlockEntitlement();
            if (shouldBlockEntitlement) {
                result.add(SubscriptionEventType.PAUSE_ENTITLEMENT);
            }
            final boolean shouldBlockBilling = isBillingStarted && !isBillingStopped && !stateBefore.isBlockBilling() && stateAfter.isBlockBilling();
            if (shouldBlockBilling) {
                result.add(SubscriptionEventType.PAUSE_BILLING);
            }

            if (!shouldResumeEntitlement && !shouldBlockEntitlement && !shouldBlockEntitlement && !shouldBlockBilling && !bs.getService().equals(DefaultEntitlementService.ENTITLEMENT_SERVICE_NAME)) {
                result.add(SubscriptionEventType.SERVICE_STATE_CHANGE);
            }
            return result;
        }

        private BlockingAggregator getState() {
            final DefaultBlockingAggregator aggrBefore = new DefaultBlockingAggregator();
            for (BlockingState cur : perServiceBlockingState.values()) {
                aggrBefore.or(cur);
            }
            return aggrBefore;
        }
    }


    private static final class DefaultSubscriptionEvent implements SubscriptionEvent {

        private final UUID id;
        private final UUID entitlementId;
        private final DateTime effectiveDate;
        private final DateTime requestedDate;
        private final SubscriptionEventType eventType;
        private final boolean isBlockingEntitlement;
        private final boolean isBlockingBilling;
        private final String serviceName;
        private final String serviceStateName;
        private final Product prevProduct;
        private final Plan prevPlan;
        private final PlanPhase prevPlanPhase;
        private final PriceList prevPriceList;
        private final BillingPeriod prevBillingPeriod;
        private final Product nextProduct;
        private final Plan nextPlan;
        private final PlanPhase nextPlanPhase;
        private final PriceList nextPriceList;
        private final BillingPeriod nextBillingPeriod;
        private final DateTime createdDate;
        private final DateTimeZone accountTimeZone;


        private DefaultSubscriptionEvent(final UUID id,
                                         final UUID entitlementId,
                                         final DateTime effectiveDate,
                                         final DateTime requestedDate,
                                         final SubscriptionEventType eventType,
                                         final boolean blockingEntitlement,
                                         final boolean blockingBilling,
                                         final String serviceName,
                                         final String serviceStateName,
                                         final Product prevProduct,
                                         final Plan prevPlan,
                                         final PlanPhase prevPlanPhase,
                                         final PriceList prevPriceList,
                                         final BillingPeriod prevBillingPeriod,
                                         final Product nextProduct,
                                         final Plan nextPlan,
                                         final PlanPhase nextPlanPhase,
                                         final PriceList nextPriceList,
                                         final BillingPeriod nextBillingPeriod,
                                         final DateTime createDate,
                                         final DateTimeZone accountTimeZone) {
            this.id = id;
            this.entitlementId = entitlementId;
            this.effectiveDate = effectiveDate;
            this.requestedDate = requestedDate;
            this.eventType = eventType;
            this.isBlockingEntitlement = blockingEntitlement;
            this.isBlockingBilling = blockingBilling;
            this.serviceName = serviceName;
            this.serviceStateName = serviceStateName;
            this.prevProduct = prevProduct;
            this.prevPlan = prevPlan;
            this.prevPlanPhase = prevPlanPhase;
            this.prevPriceList = prevPriceList;
            this.prevBillingPeriod = prevBillingPeriod;
            this.nextProduct = nextProduct;
            this.nextPlan = nextPlan;
            this.nextPlanPhase = nextPlanPhase;
            this.nextPriceList = nextPriceList;
            this.nextBillingPeriod = nextBillingPeriod;
            this.createdDate = createDate;
            this.accountTimeZone = accountTimeZone;
        }

        private DefaultSubscriptionEvent(DefaultSubscriptionEvent copy, SubscriptionEventType newEventType) {
            this(copy.getId(),
                 copy.getEntitlementId(),
                 copy.getEffectiveDateTime(),
                 copy.getRequestedDateTime(),
                 newEventType,
                 copy.isBlockedEntitlement(),
                 copy.isBlockedBilling(),
                 copy.getServiceName(),
                 copy.getServiceStateName(),
                 copy.getPrevProduct(),
                 copy.getPrevPlan(),
                 copy.getPrevPhase(),
                 copy.getPrevPriceList(),
                 copy.getPrevBillingPeriod(),
                 copy.getNextProduct(),
                 copy.getNextPlan(),
                 copy.getNextPhase(),
                 copy.getNextPriceList(),
                 copy.getNextBillingPeriod(),
                 copy.getCreatedDate(),
                copy.getAccountTimeZone());
        }

        public DateTimeZone getAccountTimeZone() {
            return accountTimeZone;
        }

        public DateTime getEffectiveDateTime() {
            return effectiveDate;
        }

        public DateTime getRequestedDateTime() {
            return requestedDate;
        }

        @Override
        public UUID getId() {
            return id;
        }

        @Override
        public UUID getEntitlementId() {
            return entitlementId;
        }

        @Override
        public LocalDate getEffectiveDate() {
            return effectiveDate != null ? new LocalDate(effectiveDate, accountTimeZone) : null;
        }

        @Override
        public LocalDate getRequestedDate() {
            return requestedDate != null ? new LocalDate(requestedDate, accountTimeZone) : null;
        }

        @Override
        public SubscriptionEventType getSubscriptionEventType() {
            return eventType;
        }

        @Override
        public boolean isBlockedBilling() {
            return isBlockingBilling;
        }

        @Override
        public boolean isBlockedEntitlement() {
            return isBlockingEntitlement;
        }

        @Override
        public String getServiceName() {
            return serviceName;
        }

        @Override
        public String getServiceStateName() {
            return serviceStateName;
        }

        @Override
        public Product getPrevProduct() {
            return prevProduct;
        }

        @Override
        public Plan getPrevPlan() {
            return prevPlan;
        }

        @Override
        public PlanPhase getPrevPhase() {
            return prevPlanPhase;
        }

        @Override
        public PriceList getPrevPriceList() {
            return prevPriceList;
        }

        @Override
        public BillingPeriod getPrevBillingPeriod() {
            return prevBillingPeriod;
        }

        @Override
        public Product getNextProduct() {
            return nextProduct;
        }

        @Override
        public Plan getNextPlan() {
            return nextPlan;
        }

        @Override
        public PlanPhase getNextPhase() {
            return nextPlanPhase;
        }

        @Override
        public PriceList getNextPriceList() {
            return nextPriceList;
        }

        @Override
        public BillingPeriod getNextBillingPeriod() {
            return nextBillingPeriod;
        }

        public DateTime getCreatedDate() {
            return createdDate;
        }
    }
}