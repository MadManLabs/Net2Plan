/*******************************************************************************
 * Copyright (c) 2016 Pablo Pavon Mari�o.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v2.1
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 ******************************************************************************/





 




package com.net2plan.examples.ocnbook.onlineSim;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import cern.colt.matrix.tdouble.DoubleFactory1D;
import cern.colt.matrix.tdouble.DoubleMatrix1D;

import com.net2plan.interfaces.networkDesign.Demand;
import com.net2plan.interfaces.networkDesign.Link;
import com.net2plan.interfaces.networkDesign.Net2PlanException;
import com.net2plan.interfaces.networkDesign.NetPlan;
import com.net2plan.interfaces.networkDesign.NetworkLayer;
import com.net2plan.interfaces.networkDesign.Node;
import com.net2plan.interfaces.networkDesign.ProtectionSegment;
import com.net2plan.interfaces.networkDesign.Route;
import com.net2plan.interfaces.simulation.IEventProcessor;
import com.net2plan.interfaces.simulation.SimEvent;
import com.net2plan.libraries.GraphUtils;
import com.net2plan.utils.Constants.RoutingType;
import com.net2plan.utils.InputParameter;
import com.net2plan.utils.Pair;
import com.net2plan.utils.RandomUtils;
import com.net2plan.utils.Triple;

/** 
 * Implements the reactions of a technology-agnostic network to connection requests under various CAC options, and reactions to failures and repairs under different recovery schemes.
 * 
 * The algorithm reacts to the following events: 
 * <ul>
 * <li>SimEvent.RouteAdd: Adds a route associated to the given demand. This can mean creating also a protection segment if the 1+1 protection options are active. If there is not enough resources for the route, it is not created.</li>
 * <li>SimEvent.RouteRemove: Removes the corresponding Route object, and any associated protection segments.</li>
 * <li>SimEvent.DemandModify: Modifies the offered traffic of a demand, caused by a traffic fluctuation.</li>
 * <li>SimEvent.NodesAndLinksChangeFailureState: Fails/repairs the indicated nodes and/or links, and reacts to such failures (the particular form depends on the network recovery options selected).</li>
 * </ul>
 * 
 * This module can be used to simulate a number of Connection-Admission-Control strategies, that decide on the route if the incoming connection requests. 
 * This can be done separately or in conjuntion with different network recovery schemes that can be chosen, that define how the network reacts to node/link failures and repairs.
 * 
 * This module can be used in conjunction with the {@code Online_evGen_generalGenerator} generator for simulating networks. 
 * 
 * @net2plan.keywords CAC (Connection-Admission-Control), Network recovery: protection, Network recovery: restoration
 * @net2plan.ocnbooksections Section 3.3.3, Exercise 3.7, Exercise 3.8
 * @net2plan.inputParameters 
 * @author Pablo Pavon-Marino
 */
public class Online_evProc_generalProcessor extends IEventProcessor
{
	private InputParameter cacType = new InputParameter ("cacType", "#select# alternate-routing least-congested-routing load-sharing" , "Criteria to decide the route of a connection among the available paths");
	private InputParameter shortestPathType = new InputParameter ("shortestPathType", "#select# hops km" , "Criteria to compute the shortest path. Valid values: 'hops' or 'km'");
	private InputParameter randomSeed = new InputParameter ("randomSeed", (long) 1 , "Seed for the random generator (-1 means random)");
	private InputParameter k = new InputParameter ("k", (int) 2 , "Maximum number of admissible paths per demand, from where to choose their route" , 1 , Integer.MAX_VALUE);
	private InputParameter maxLengthInKm = new InputParameter ("maxLengthInKm", (double) -1 , "Paths longer than this are considered not admissible. A non-positive number means this limit does not exist");
	private InputParameter maxNumHops = new InputParameter ("maxNumHops", (int) -1 , "The path from an origin to any destination in cannot have more than this number of hops. A non-positive number means this limit does not exist");
	private InputParameter layerId = new InputParameter ("layerId", (long) -1 , "Layer containing traffic demands (-1 means default layer)");
	private InputParameter recoveryType = new InputParameter ("recoveryType", "#select# protection restoration none" , "None, nothing is done, so affected routes fail. Restoration, affected routes are visited sequentially, and we try to reroute them in the available capacity; in protection, affected routes are rerouted using the protection segments.");
	private InputParameter removePreviousRoutes = new InputParameter ("removePreviousRoutes", false  , "If true, previous routes are removed from the system.");
	private InputParameter protectionTypeToNewRoutes = new InputParameter ("protectionTypeToNewRoutes", "#select# 1+1-link-disjoint 1+1-node-disjoint srg-disjoint none" , "The new routes to add, may be associated a 1+1 segment protection (link, node or SRG disjoint), or no segment protection is added (none)");
	
	private List<Node> nodes;
	private NetworkLayer layer;
	private Map<Route,List<Link>> routeOriginalLinks;
	private Map<Demand,List<List<Link>>> cpl;
	private Map<Demand,List<Pair<List<Link>,List<Link>>>> cpl11; 

	private boolean newRoutesHave11Protection;
	private boolean isRestorationRecovery , isProtectionRecovery , isShortestPathNumHops;
	private boolean isAlternateRouting , isLeastCongestedRouting , isLoadSharing;
	private Random rng;

	private double stat_trafficOfferedConnections , stat_trafficCarriedConnections;
	private double stat_trafficAttemptedToRecoverConnections , stat_trafficSuccessfullyRecoveredConnections;
	private long stat_numOfferedConnections , stat_numCarriedConnections;
	private long stat_numAttemptedToRecoverConnections , stat_numSuccessfullyRecoveredConnections;
	private double stat_transitoryInitTime;

	
	@Override
	public String getDescription()
	{
		return "Implements the reactions of a technology-agnostic network to connection requests under various CAC options, and reactions to failures and repairs under different recovery schemes.";
	}
	
	
	
	@Override
	public List<Triple<String, String, String>> getParameters()
	{
		/* Returns the parameter information for all the InputParameter objects defined in this object (uses Java reflection) */
		return InputParameter.getInformationAllInputParameterFieldsOfObject(this);
	}

	@Override
	public void initialize(NetPlan initialNetPlan, Map<String, String> algorithmParameters, Map<String, String> simulationParameters, Map<String, String> net2planParameters)
	{
		/* Initialize all InputParameter objects defined in this object (this uses Java reflection) */
		InputParameter.initializeAllInputParameterFieldsOfObject(this, algorithmParameters);
		
		this.layer = layerId.getLong () == -1? initialNetPlan.getNetworkLayerDefault() : initialNetPlan.getNetworkLayerFromId(layerId.getLong ());
		this.nodes = initialNetPlan.getNodes ();
		this.isShortestPathNumHops = shortestPathType.getString ().equalsIgnoreCase("hops");
		this.routeOriginalLinks = new HashMap<Route,List<Link>> ();
		this.isRestorationRecovery = recoveryType.getString ().equalsIgnoreCase("restoration");
		this.isProtectionRecovery = recoveryType.getString ().equalsIgnoreCase("protection");
		this.isAlternateRouting = cacType.getString().equalsIgnoreCase("alternate-routing");
		this.isLeastCongestedRouting = cacType.getString().equalsIgnoreCase("least-congested-routing");
		this.isLoadSharing = cacType.getString().equalsIgnoreCase("load-sharing");
		this.newRoutesHave11Protection = !protectionTypeToNewRoutes.getString ().equalsIgnoreCase("none");
		this.rng = new Random(randomSeed.getLong () == -1? (long) RandomUtils.random(0, Long.MAX_VALUE - 1) : randomSeed.getLong ());
		if (!isProtectionRecovery && newRoutesHave11Protection) throw new Net2PlanException ("In the input parameter you ask to assign protection paths to new connections, while the recovery type chosen does not use them");
		
		/* Compute the candidate path list */
		final int E = initialNetPlan.getNumberOfLinks(layer);
		final DoubleMatrix1D linkCostVector = isShortestPathNumHops? DoubleFactory1D.dense.make (E , 1.0) : initialNetPlan.getVectorLinkLengthInKm();
		this.cpl = initialNetPlan.computeUnicastCandidatePathList(linkCostVector.toArray() , "K", Integer.toString(k.getInt ()), "maxLengthInKm", Double.toString(maxLengthInKm.getDouble () > 0? maxLengthInKm.getDouble () : Double.MAX_VALUE) , "maxNumHops", Integer.toString(maxNumHops.getInt () > 0? maxNumHops.getInt () : Integer.MAX_VALUE));
		final int protectionTypeCode = protectionTypeToNewRoutes.equals("srg-disjoint") ? 0 : protectionTypeToNewRoutes.equals("1+1-node-disjoint")? 1 : 2;
		this.cpl11 = !newRoutesHave11Protection? null : NetPlan.computeUnicastCandidate11PathList(cpl, linkCostVector, protectionTypeCode); 

		
		//		System.out.println ("cpl: " + cpl);
		System.out.println ("cpl11: " + cpl11);
		
		if (removePreviousRoutes.getBoolean())
		{
			initialNetPlan.removeAllUnicastRoutingInformation(layer);
			initialNetPlan.removeAllMulticastTrees(layer);
		}

		initialNetPlan.setRoutingType(RoutingType.SOURCE_ROUTING);
		for (Route r : initialNetPlan.getRoutes(layer)) routeOriginalLinks.put (r , r.getSeqLinksRealPath());
		this.finishTransitory(0);
	}

	@Override
	public void processEvent(NetPlan currentNetPlan, SimEvent event)
	{
		if (event.getEventObject () instanceof SimEvent.RouteAdd)
		{
			SimEvent.RouteAdd addRouteEvent = (SimEvent.RouteAdd) event.getEventObject ();
			if (addRouteEvent.demand.getLayer() != this.layer) throw new Net2PlanException ("Routes cannot be added at layers different to layer " + layerId.getLong ());

			System.out.println ("Recevie RouteAdd: " + addRouteEvent + ", demand: " + addRouteEvent.demand);
			/* update the offered traffic of the demand */
			this.stat_numOfferedConnections ++;
			this.stat_trafficOfferedConnections += addRouteEvent.carriedTraffic;
			
			/* Computes one or two paths over the links (the second path would be a segment). You cannot use the already existing segments in these paths */
			if (newRoutesHave11Protection)
			{
				Pair<List<Link>,List<Link>> spLinks = computeValid11PathPairNewRoute(addRouteEvent.demand , addRouteEvent.occupiedLinkCapacity); // cannot use protection segments for this path, since the route still does not have them!!
//				System.out.println ("protectionTypeToNewRoutes: " + protectionTypeToNewRoutes.getString());
//				System.out.println ("spLinks : " + spLinks);
				if (spLinks != null)
				{
					final Route addedRoute = currentNetPlan.addRoute(addRouteEvent.demand , addRouteEvent.carriedTraffic , addRouteEvent.occupiedLinkCapacity, spLinks.getFirst() , null);
					final ProtectionSegment addedSegment = currentNetPlan.addProtectionSegment(spLinks.getSecond() , addRouteEvent.occupiedLinkCapacity , null);
					addedRoute.addProtectionSegment(addedSegment);
					addRouteEvent.routeAddedToFillByProcessor = addedRoute;
					this.routeOriginalLinks.put (addedRoute , spLinks.getFirst());
					this.stat_numCarriedConnections ++;
					this.stat_trafficCarriedConnections += addRouteEvent.carriedTraffic;
					System.out.println ("Added route: " + addedRoute);
				}
			}
			else
			{
				List<Link> spLinks = computeValidPathNewRoute(addRouteEvent.demand , addRouteEvent.occupiedLinkCapacity); // cannot use protection segments for this path, since the route still does not have them!!
				if (!spLinks.isEmpty())
				{
					final Route addedRoute = currentNetPlan.addRoute(addRouteEvent.demand , addRouteEvent.carriedTraffic , addRouteEvent.occupiedLinkCapacity, spLinks , null);
					this.routeOriginalLinks.put (addedRoute , spLinks);
					this.stat_numCarriedConnections ++;
					this.stat_trafficCarriedConnections += addRouteEvent.carriedTraffic;
					addRouteEvent.routeAddedToFillByProcessor = addedRoute;
				}
			}
			
		}
		else if (event.getEventObject () instanceof SimEvent.RouteRemove)
		{
			SimEvent.RouteRemove routeEvent = (SimEvent.RouteRemove) event.getEventObject ();
			Route routeToRemove = routeEvent.route;
			if (routeToRemove == null) throw new RuntimeException ("Bad");
			routeToRemove.remove();
			for (ProtectionSegment s : routeToRemove.getPotentialBackupProtectionSegments()) s.remove ();
			this.routeOriginalLinks.remove(routeToRemove);
		} else if (event.getEventObject () instanceof SimEvent.DemandModify)
		{
			SimEvent.DemandModify ev = (SimEvent.DemandModify) event.getEventObject ();
			Demand d = ev.demand; 
			if (ev.modificationIsRelativeToCurrentOfferedTraffic) 
				d.setOfferedTraffic(d.getOfferedTraffic() + ev.offeredTraffic);
			else
				d.setOfferedTraffic(ev.offeredTraffic);
		} else if (event.getEventObject () instanceof SimEvent.NodesAndLinksChangeFailureState)
		{
			SimEvent.NodesAndLinksChangeFailureState ev = (SimEvent.NodesAndLinksChangeFailureState) event.getEventObject ();

//			System.out.println ("Event NodesAndLinksChangeFailureState: links up" + ev.linksUp + ", links down: " + ev.linksDown);
			/* This automatically sets as up the routes affected by a repair in its current path, and sets as down the affected by a failure in its current path */
			currentNetPlan.setLinksAndNodesFailureState(ev.linksToUp , ev.linksToDown , ev.nodesToUp , ev.nodesToDown);
			
			if (isProtectionRecovery || isRestorationRecovery)
			{
				/* Try to reroute the routes that are still failing */
				for (Route r : new HashSet<Route> (currentNetPlan.getRoutesDown(layer)))
				{
					this.stat_numAttemptedToRecoverConnections ++;
					this.stat_trafficAttemptedToRecoverConnections += r.getCarriedTrafficInNoFailureState();
					List<Link> spLinks = isProtectionRecovery? computeValidReroute_protection(r) : computeValidPathNewRoute (r.getDemand() , r.getOccupiedCapacityInNoFailureState());
					if (!spLinks.isEmpty()) 
					{ 
						r.setSeqLinksAndProtectionSegments(spLinks);
						this.stat_numSuccessfullyRecoveredConnections ++; 
						this.stat_trafficSuccessfullyRecoveredConnections += r.getCarriedTrafficInNoFailureState(); 
					}
				}
			}
		}
		else throw new Net2PlanException ("Unknown event type: " + event);
	}	
	@Override
	public String finish(StringBuilder output, double simTime)
	{
		final double trafficBlockingOnConnectionSetup = stat_trafficOfferedConnections == 0? 0 : 1 - (stat_trafficCarriedConnections / stat_trafficOfferedConnections );
		final double connectionBlockingOnConnectionSetup = stat_numOfferedConnections == 0? 0.0 : 1 - (((double) stat_numCarriedConnections) / ((double) stat_numOfferedConnections));
		final double successProbabilityRecovery = stat_numAttemptedToRecoverConnections == 0? 0.0 : (((double) stat_numSuccessfullyRecoveredConnections) / ((double) stat_numAttemptedToRecoverConnections));
		final double successProbabilityTrafficRecovery = stat_trafficAttemptedToRecoverConnections == 0? 0.0 : (((double) stat_trafficSuccessfullyRecoveredConnections) / ((double) stat_trafficAttemptedToRecoverConnections));
		final double dataTime = simTime - stat_transitoryInitTime;
		if (dataTime <= 0) { output.append ("<p>No time for data acquisition</p>"); return ""; }

		output.append (String.format("<p>Total traffic of offered connections: number connections %d, total time average traffic: %f</p>", stat_numOfferedConnections, stat_trafficOfferedConnections / dataTime));
		output.append (String.format("<p>Total traffic of carried connections: number connections %d, total time average traffic: %f</p>", stat_numCarriedConnections, stat_trafficCarriedConnections / dataTime));
		output.append (String.format("<p>Blocking at connection setup: Probability of blocking a connection: %f, Fraction of blocked traffic: %f</p>", connectionBlockingOnConnectionSetup , trafficBlockingOnConnectionSetup));
		output.append (String.format("<p>Number connections attempted to recover: %d (summing time average traffic: %f). </p>", stat_numAttemptedToRecoverConnections, stat_trafficAttemptedToRecoverConnections / dataTime));
		output.append (String.format("<p>Number connections successfully recovered: %d (summing time average traffic: %f). </p>", stat_numSuccessfullyRecoveredConnections, stat_trafficSuccessfullyRecoveredConnections / dataTime));
		output.append (String.format("<p>Probability of successfully recover a connection: %f. Proability weighted by the connection traffic: %f). </p>", successProbabilityRecovery, successProbabilityTrafficRecovery));
//		output.append (String.format("<p>Total traffic carried at current state: %f. </p>", -1);
		return "";
	}

	@Override
	public void finishTransitory(double simTime)
	{
		this.stat_trafficOfferedConnections = 0;
		this.stat_trafficCarriedConnections = 0;
		this.stat_trafficAttemptedToRecoverConnections  = 0;
		this.stat_trafficSuccessfullyRecoveredConnections = 0;
		this.stat_numOfferedConnections = 0;
		this.stat_numCarriedConnections = 0;
		this.stat_numAttemptedToRecoverConnections = 0;
		this.stat_numSuccessfullyRecoveredConnections = 0;
		this.stat_transitoryInitTime = simTime;
	}

	/* down links or segments cannot be used */
	private List<Link> computeValidPathNewRoute (Demand demand , double occupiedLinkCapacity)
	{
//		System.out.println ("computeValidPathNewRoute, demand: " + demand + " occupied: " + occupiedLinkCapacity);
		final List<List<Link>> paths = cpl.get(demand);
		/* If load sharing */
		if (isLoadSharing)
		{
			final int randomChosenIndex = rng.nextInt(paths.size());
			final List<Link> seqLinks = cpl.get(demand).get(randomChosenIndex);
			if (isValidPath(seqLinks, occupiedLinkCapacity).getFirst()) return seqLinks; else return new LinkedList<Link> ();
		}
		/* If alternate or LCR */
		List<Link> lcrSoFar = null; double lcrIdleCapacitySoFar = -Double.MAX_VALUE;
		for (List<Link> seqLinks : paths)
		{
			Pair<Boolean,Double> isValid = isValidPath(seqLinks, occupiedLinkCapacity);
//			System.out.println ("path: " + seqLinks + ", occuied: " + occupiedLinkCapacity + ", isValid: " + isValid);
			if (isValid.getFirst()) 
			{
				if (isAlternateRouting) return seqLinks; 
				if (isValid.getSecond() > lcrIdleCapacitySoFar)
				{
					lcrIdleCapacitySoFar = isValid.getSecond();
					lcrSoFar = seqLinks;
				}
			}
		}
		return (isAlternateRouting || (lcrSoFar == null))? new LinkedList<Link> () : lcrSoFar; 
	}

	private Pair<List<Link>,List<Link>> computeValid11PathPairNewRoute (Demand demand , double occupiedLinkCapacity)
	{
		final List<Pair<List<Link>,List<Link>>> pathPairs = cpl11.get(demand);
		/* If load sharing */
		if (isLoadSharing)
		{
			final int randomChosenIndex = rng.nextInt(pathPairs.size());
			final Pair<List<Link>,List<Link>> pathPair = pathPairs.get(randomChosenIndex);
			if (isValidPath(pathPair.getFirst(), occupiedLinkCapacity).getFirst() && isValidPath(pathPair.getSecond(), occupiedLinkCapacity).getFirst ())
				return pathPair; else return null;
		}

		Pair<List<Link>,List<Link>> lcrSoFar = null; double lcrIdleCapacitySoFar= -Double.MAX_VALUE;
		for (Pair<List<Link>,List<Link>> pathPair : this.cpl11.get(demand))
		{
			Pair<Boolean,Double> validityFirstPath = isValidPath(pathPair.getFirst(), occupiedLinkCapacity); 
			if (!validityFirstPath.getFirst()) continue;
			Pair<Boolean,Double> validitySecondPath = isValidPath(pathPair.getSecond(), occupiedLinkCapacity); 
			if (!validitySecondPath.getFirst()) continue;
			if (isAlternateRouting) return pathPair;
			final double thisPairIdleCapacity = Math.min(validityFirstPath.getSecond(), validitySecondPath.getSecond());
			if (thisPairIdleCapacity > lcrIdleCapacitySoFar)
			{
				lcrIdleCapacitySoFar = thisPairIdleCapacity;
				lcrSoFar = pathPair;
			}
		}
		return lcrSoFar; //if alternate, this is null also
	}

	/* down links or segments cannot be used */
	private List<Link> computeValidReroute_protection (Route routeToReroute)
	{
		Map<Link,Double> costMap = new HashMap<Link,Double> (); Set<Link> linkMap = new HashSet<Link> (); 
		final double minimumCapacityNeeded = routeToReroute.getOccupiedCapacityInNoFailureState();
		for (Link e : this.routeOriginalLinks.get(routeToReroute)) 
			if (!e.isDown() && !e.getOriginNode().isDown() && !e.getDestinationNode().isDown())
				if (e.getCapacity() - e.getReservedCapacityForProtection() - e.getCarriedTrafficNotIncludingProtectionSegments() >= minimumCapacityNeeded) 
				{
					linkMap.add (e);
					costMap.put (e , isShortestPathNumHops? 1 : e.getLengthInKm());
				}
					
		for (ProtectionSegment s : routeToReroute.getPotentialBackupProtectionSegments()) 
			if ((s.getReservedCapacityForProtection() - s.getCarriedTraffic() >= minimumCapacityNeeded) && (!s.isDown()))
			{ 
				linkMap.add (s);
				costMap.put (s , isShortestPathNumHops? s.getNumberOfHops() : s.getLengthInKm()); 
			}

		List<Link> res = GraphUtils.getShortestPath(nodes , linkMap , routeToReroute.getIngressNode() , routeToReroute.getEgressNode() , costMap);
		return res;
	}

	private Pair<Boolean,Double> isValidPath (List<Link> seqLinks , double routeOccupiedCapacity)
	{
		final double minimumCapacityNeeded = routeOccupiedCapacity;
		boolean validPath = true; double thisPathIdleCapacity = Double.MAX_VALUE; 
		for (Link e : seqLinks) 
		{
			final double thisLinkIdleCapacity = e.getCapacity() - e.getCarriedTrafficNotIncludingProtectionSegments();
			thisPathIdleCapacity = Math.min(thisPathIdleCapacity, thisLinkIdleCapacity);
			if (e.isDown() || e.getOriginNode().isDown() || e.getDestinationNode().isDown() || (thisLinkIdleCapacity <= minimumCapacityNeeded))
			{ validPath = false; break; }
		}
		return Pair.of(validPath, thisPathIdleCapacity);
	}
}
