package solvers;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import models.AttackMethod;
import models.DARMSModel;
import models.PassengerDistribution;
import models.Flight;
import models.PayoffStructure;
import models.RiskCategory;
import models.ScreeningOperation;
import models.ScreeningResource;
import ilog.concert.*;
import ilog.cplex.*;

public class DARMSMarginalSolver{
	private DARMSModel model;
	private IloCplex cplex;
	
	private Map<RiskCategory, IloNumVar> dMap;
	private Map<Integer, Map<RiskCategory, Map<Flight, Map<AttackMethod, IloNumVar>>>> aMap;
	
		private Map<Integer, HashMap<ScreeningResource, IloNumVar>> ovMap;
	
	// m = slope = Map(w, i, c(f, theta), t, CPLEX variable)
	private Map<Integer, Map<Integer, Map<Flight, Map<RiskCategory, Map<ScreeningOperation, IloNumVar>>>>> mMap;
	
	// b = y-intercept = Map(w, c(f, theta), t, CPLEX variable)
	private Map<Integer, Map<Flight, Map<RiskCategory, Map<ScreeningOperation, IloNumVar>>>> bMap;

	// where we will store the answers
	private Map<Integer, Map<ScreeningResource, Double>>  defenderOverflowStrategy;	
	private Map<Integer, Map<Flight, Map<RiskCategory, Map<ScreeningOperation, Double>>>> defenderScreeningStrategyb;
	private Map<Integer,Map< Integer, Map<Flight,Map<RiskCategory,Map<ScreeningOperation,Double>>>>> defenderScreeningStrategym;
	private Map<Integer, Map<RiskCategory, Map<Flight, Map<AttackMethod, Double>>>> riskCategoryCoverage;
	private Map<RiskCategory, Double> defenderPayoffs;
	private Map<RiskCategory, Double> adversaryPayoffs;
	private Map<RiskCategory, Map<Integer, Map<Flight, AttackMethod>>> adversaryStrategies;
	
	private Map<Map<Integer, Map<Flight, Map<RiskCategory, Map<ScreeningOperation, Double>>>>, Double> marginalBounds;
	
	private List<IloRange> constraints;
	
	private static final double MM = Double.MAX_VALUE;
	
	private List<Integer> allTimeWindows;
	private List<Integer> currentTimeWindows;
	
	private List<PassengerDistribution> xiDistribution;
	
	private PayoffStructure payoffStructure;
	
	private boolean zeroSum;
	private boolean decomposed;
	private boolean flightByFlight;
	private boolean naive;

	private double expEpsilon;

	private double solverTime;
	
	public DARMSMarginalSolver(DARMSModel model, boolean zeroSum, boolean decomposed, boolean flightByFlight, boolean naive) throws Exception{
		this.model = model;
		this.xiDistribution = model.getXiDistribution();
		this.payoffStructure = model.getPayoffStructure();
		this.zeroSum = zeroSum;
		this.decomposed = decomposed;
		this.flightByFlight = flightByFlight;
		this.naive = naive;
		
		if(zeroSum){
			verifyZeroSum();
		}
		
		marginalBounds = new HashMap<Map<Integer, Map<Flight, Map<RiskCategory, Map<ScreeningOperation, Double>>>>, Double>();
		
		allTimeWindows = model.getTimeWindows();
	}
	
	public DARMSMarginalSolver(DARMSModel model, PassengerDistribution passengerDistribution, boolean zeroSum, boolean decomposed, boolean flightByFlight, boolean naive) throws Exception{
		this.model = model;
		this.xiDistribution = model.getXiDistribution();
		this.payoffStructure = model.getPayoffStructure();
		this.zeroSum = zeroSum;
		this.decomposed = decomposed;
		this.flightByFlight = flightByFlight;
		this.naive = naive;
		
		if(zeroSum){
			verifyZeroSum();
		}
		
		marginalBounds = new HashMap<Map<Integer, Map<Flight, Map<RiskCategory, Map<ScreeningOperation, Double>>>>, Double>();
		
		allTimeWindows = model.getTimeWindows();
	}
	
	public DARMSMarginalSolver(DARMSModel model, PayoffStructure payoffStructure, boolean zeroSum, boolean decomposed, boolean flightByFlight, boolean naive) throws Exception{
		this.model = model;
		this.xiDistribution = model.getXiDistribution();
		this.payoffStructure = payoffStructure;
		this.zeroSum = zeroSum;
		this.decomposed = decomposed;
		this.flightByFlight = flightByFlight;
		this.naive = naive;
		
		if(zeroSum){
			verifyZeroSum();
		}
		
		marginalBounds = new HashMap<Map<Integer, Map<Flight, Map<RiskCategory, Map<ScreeningOperation, Double>>>>, Double>();
		
		allTimeWindows = model.getTimeWindows();
	}
	
	private void verifyZeroSum() throws Exception{
		for(Flight f : model.getFlights()){
			int defCov = payoffStructure.defCov(f);
			int defUncov = payoffStructure.defUncov(f);
			int attCov = payoffStructure.attCov(f);
			int attUncov = payoffStructure.attUncov(f);
			
			if(defCov != -attCov || defUncov != -attUncov){
				throw new Exception("Attempting to use zero-sum formulation on a non-zero-sum game.");
			}
		}
	}
	
	private void loadProblem(List<Integer> timeWindows) throws IloException{
		
		if( model.hasOverflow ){
			ovMap = new HashMap<Integer, HashMap<ScreeningResource, IloNumVar>>();
		}
		
		// sMap = new HashMap<Integer, Map<Flight, Map<RiskCategory, Map<ScreeningOperation, IloNumVar>>>>();
		// REMOVED
		// pMap = new HashMap<Integer, Map<Flight, Map<PostScreeningResource, IloNumVar>>>();
		dMap = new HashMap<RiskCategory, IloNumVar>();
		
		// ADDED
		mMap = new HashMap<Integer, Map<Integer, Map<Flight, Map<RiskCategory, Map<ScreeningOperation, IloNumVar>>>>>();
		bMap = new HashMap<Integer, Map<Flight, Map<RiskCategory, Map<ScreeningOperation, IloNumVar>>>>();
		// END ADD
		
		cplex = new IloCplex();
		cplex.setName("DARMS");
		cplex.setParam(IloCplex.IntParam.RootAlg, 4);
		cplex.setParam(IloCplex.IntParam.BarCrossAlg, -1);
		// cplex.setOut(null);
		
		this.currentTimeWindows = timeWindows;
		System.out.println("Initializing Variables...");
		initVars();
		System.out.println("Initializing Constraints...");
		initConstraints();
		System.out.println("Initializing Objective...");
		initObjective();
		System.out.println("Begin Solving...");
	}
	
	private void initVars() throws IloException{
		List<IloNumVar> varList = new ArrayList<IloNumVar>();
		
		// ADDED: mMap
		int counter = 0;
		for(int t : currentTimeWindows){
			mMap.put(t, new HashMap<Integer, Map<Flight, Map<RiskCategory, Map<ScreeningOperation, IloNumVar>>>>());
			
			List<Integer> subListTimeWindows = currentTimeWindows.subList(0, counter);
			counter = counter + 1;
			
			for(int it : subListTimeWindows){
				mMap.get(t).put(it, new HashMap<Flight, Map<RiskCategory, Map<ScreeningOperation, IloNumVar>>>());
			
				for(Flight f : model.getFlights(t)){
					mMap.get(t).get(it).put(f, new HashMap<RiskCategory, Map<ScreeningOperation, IloNumVar>>());
				
					for(RiskCategory c : model.getAdversaryDistribution().keySet()){
						mMap.get(t).get(it).get(f).put(c, new HashMap<ScreeningOperation, IloNumVar>());
					
						for(ScreeningOperation o : model.getScreeningOperations()){
							IloNumVar var = cplex.numVar(-MM, MM, IloNumVarType.Float, "m_t" + t + "_i" + it + "_f" +  f.id() + "_c" + c.id() + "_o" + o.getID());
					
							mMap.get(t).get(it).get(f).get(c).put(o, var);
							varList.add(var);
						}
					}
				}
			}
		}
			
		// ADDED: bMap
			
		for(int t : currentTimeWindows){
			bMap.put(t, new HashMap<Flight, Map<RiskCategory, Map<ScreeningOperation, IloNumVar>>>());
			
			for(Flight f : model.getFlights(t)){
				bMap.get(t).put(f, new HashMap<RiskCategory, Map<ScreeningOperation, IloNumVar>>());
				
				for(RiskCategory c : model.getAdversaryDistribution().keySet()){
					bMap.get(t).get(f).put(c, new HashMap<ScreeningOperation, IloNumVar>());
					
					for(ScreeningOperation o : model.getScreeningOperations()){
						IloNumVar var = cplex.numVar(-MM, MM, IloNumVarType.Float, "b_t" + t + "_f" +  f.id() + "_c" + c.id() + "_o" + o.getID());
					
						bMap.get(t).get(f).get(c).put(o, var);
						varList.add(var);
					}
				}
			}
		}
		
		
		
		// ADDITION: Initialize overflow variables
				// For all the time windows but the last...
		if( model.hasOverflow){		
			for(int i = 0; i < currentTimeWindows.size() - 1; i++ ){
				int t = currentTimeWindows.get(i);
				
				// ...Create a map of resources to overflow value...
				HashMap<ScreeningResource, IloNumVar> hm = new HashMap<ScreeningResource, IloNumVar>();
				ovMap.put(t, hm);
				
				// ...For all the screening resources...
				for(ScreeningResource r : model.getScreeningResources().keySet() ){				
					IloNumVar var = cplex.numVar(0.0, MM, IloNumVarType.Float, "o_t" + t + "_r" + r.id());
						
							ovMap.get(t).put(r, var);
							varList.add(var);
						}
			}
		}
		// END ADD
		
		// Kept: dmap which is the same as s_theta
		for(RiskCategory c : model.getAdversaryDistribution().keySet()){
			IloNumVar var1 = cplex.numVar(-MM, MM, IloNumVarType.Float, "d_c" + c.id());
				
			dMap.put(c, var1);
			
			varList.add(var1);
		}
		
		IloNumVar[] v = new IloNumVar[varList.size()];

		cplex.add(varList.toArray(v));
	}
	
	private void initConstraints() throws IloException{
		constraints = new ArrayList<IloRange>();
		System.out.println("Initializing Probability Constraints...");
		sumDefenderScreeningActionRow();
		System.out.println("Initializing Throughput Constraints...");
		sumDefenderScreeningThroughputRow();
		System.out.println("Initializing Utility Constraints...");
		sumDefenderCoverageRow();
	}
	
	private void initObjective() throws IloException{
		Map<RiskCategory, Double> adversaryDistribution = model.getAdversaryDistribution();
		
		IloNumExpr expr = cplex.constant(0);
		
		for(RiskCategory c : model.getAdversaryDistribution().keySet()){
			expr = cplex.sum(expr, cplex.prod(dMap.get(c), adversaryDistribution.get(c)));
		}
		
		// ADDITION: subtract fine times overflow from obj func for all the time windows but the last
		if( model.hasOverflow ){
			for(int i = 0; i < currentTimeWindows.size() - 1; i++ ){
				int t = currentTimeWindows.get(i);
				for(ScreeningResource r : model.getScreeningResources().keySet()){
					expr = cplex.sum(expr, cplex.negative(cplex.prod(ovMap.get(t).get(r), model.getResourceFines().get(t).get(r))));
				}
			}
		}
		
		cplex.addMaximize(expr);
	}
	
	public void solve() throws Exception{
		defenderScreeningStrategym = new HashMap<Integer, Map<Integer, Map<Flight, Map<RiskCategory, Map<ScreeningOperation, Double>>>>>();
		defenderScreeningStrategyb = new HashMap<Integer, Map<Flight, Map<RiskCategory, Map<ScreeningOperation, Double>>>>();
		riskCategoryCoverage = new HashMap<Integer, Map<RiskCategory, Map<Flight, Map<AttackMethod, Double>>>>();
		defenderPayoffs = new HashMap<RiskCategory, Double>();
		adversaryPayoffs = new HashMap<RiskCategory, Double>();
		adversaryStrategies = new HashMap<RiskCategory, Map<Integer, Map<Flight, AttackMethod>>>();
		
		List<RiskCategory> riskCategories = new ArrayList<RiskCategory>(model.getAdversaryDistribution().keySet());
		Collections.sort(riskCategories);
		
		if(decomposed){
			for(int t : allTimeWindows){
				List<Integer> timeWindow = new ArrayList<Integer>();
				
				timeWindow.add(t);
				
				loadProblem(timeWindow);
				
				cplex.solve();
				
				if(!cplex.isPrimalFeasible()){
					throw new Exception("Infeasible. Capacity constraints exceeded. Time Window: " + t);
				}
				
				defenderScreeningStrategym.put(t, getDefenderScreeningStrategym().get(t));
				defenderScreeningStrategyb.put(t, getDefenderScreeningStrategyb().get(t));
				
				if( model.hasOverflow){
					defenderOverflowStrategy.put(t,  (HashMap<ScreeningResource, Double>) getDefenderOverflowStrategy().get(t) );
				}
				riskCategoryCoverage.put(t, calculateRiskCategoryCoverage().get(t));
				
				Map<RiskCategory, Double> dPayoffs = getDefenderPayoffs();
				Map<RiskCategory, Map<Integer, Map<Flight, AttackMethod>>> aStrategies = getAdversaryStrategies();
				Map<RiskCategory, Double> aPayoffs = getAdversaryPayoffs();
				
				for(RiskCategory c : riskCategories){
					if(!adversaryPayoffs.containsKey(c) || aPayoffs.get(c) > adversaryPayoffs.get(c)){
						defenderPayoffs.put(c, dPayoffs.get(c));
						adversaryPayoffs.put(c, aPayoffs.get(c));
						adversaryStrategies.put(c, aStrategies.get(c));
					}
				}
			}
		}
		else{
			loadProblem(allTimeWindows);
			// writeProblem("DARMS.lp");
			
			
			long start2 = System.currentTimeMillis();
			cplex.solve();
			double solverRuntime = (System.currentTimeMillis() - start2) / 1000.0;
			this.solverTime = solverRuntime;
			
			// writeSolution("DARMS2.sol");
			
			if(!cplex.isPrimalFeasible()){
				writeProblem("Infeasible.lp");
				System.out.println( "This is infeasible");
				//writeProblem("Infeasible.sol.txt");
				throw new Exception("Infeasible. Capacity constraints exceeded.");
			}
			
			defenderScreeningStrategym = getDefenderScreeningStrategym();
			defenderScreeningStrategyb = getDefenderScreeningStrategyb();
			
			if( model.hasOverflow ){
				defenderOverflowStrategy = getDefenderOverflowStrategy();
			}
			riskCategoryCoverage = calculateRiskCategoryCoverage();
			defenderPayoffs = getDefenderPayoffs();
		}
	}
	
	private void sumDefenderCoverageRow() throws IloException{
		int counter = 0;
		IloNumExpr expr = cplex.constant(0);
		IloNumExpr expr2 = cplex.constant(0);
		IloNumExpr expr3 = cplex.constant(0);
		
		for(int t : currentTimeWindows){
			List<Integer> subListTimeWindows = currentTimeWindows.subList(0, counter);
			counter = counter + 1;
			for( PassengerDistribution xi : xiDistribution ){
				for(RiskCategory c : model.getAdversaryDistribution().keySet()){
					for(Flight f : model.getFlights(t)){
						for(AttackMethod m : model.getAttackMethods()){
							
							// ADDED
							
							expr = expr3;
							for(ScreeningOperation o : model.getScreeningOperations()){
								expr2 = expr3;
								for(int i : subListTimeWindows){
									// sum ( m * xi ) over all prev time window
									expr2 = cplex.sum(expr2, cplex.prod(mMap.get(t).get(i).get(f).get(c).get(o), xi.get(i, f, c)));
									// System.out.println( (payoffStructure.defUncov(f) - payoffStructure.defCov(f))*( o.effectiveness(c, m)) * xi.get(i, f, c) );
								}
								// Plus b_{f,c,team}^{current window}
								expr2 = cplex.sum(expr2, bMap.get(t).get(f).get(c).get(o) );
								// Times effectiveness
								// QUESTION: why is effectiveness negative?
								expr2 = cplex.prod(expr2, o.effectiveness(c, m));
								// This product is added to the sum over all teams
								expr = cplex.sum(expr, expr2);
							}
							
							expr = cplex.sum(dMap.get(c), cplex.prod(expr, payoffStructure.defUncov(f) - payoffStructure.defCov(f)));
							//cplex.add(cplex.le(expr, f.getDefUncovPayoff(), "DC" + t + "C" + c.id() + "F" + f.id() + "M" + m.id()));
							cplex.add(cplex.le(expr, payoffStructure.defUncov(f), "DEFCOVt=" + t + "c=" + c.id() + "f=" + f.id() + "m=" + m.id() +"xi=" + xi.toString() ));
						}
					}
				}
			}
		}
	}
	
	private void sumDefenderScreeningActionRow() throws IloException{
		// ADDED: changed to set all slope intercepts' sums over teams to 1, and to set the sum of slopes = 0
		Collections.sort( currentTimeWindows );
		int counter = 0;
		
		IloNumExpr expr2 = cplex.constant(0);
		IloNumExpr expr = cplex.constant(0);
		IloNumExpr expr3 = cplex.constant(0);
		
		for(int t : currentTimeWindows){
			for(Flight f : model.getFlights(t)){
				for(RiskCategory c : model.getAdversaryDistribution().keySet()){
					expr = expr3;
					for(ScreeningOperation o : model.getScreeningOperations()){
						expr = cplex.sum(expr, bMap.get(t).get(f).get(c).get(o));
					}
						
					for(int i : currentTimeWindows.subList(0, counter)){
						expr2 = expr3;
						for(ScreeningOperation o2 : model.getScreeningOperations()){
							expr2 = cplex.sum(expr2, mMap.get(t).get(i).get(f).get(c).get(o2));
						}
						cplex.add(cplex.eq(expr2, 0.0, "MSUMZERO"));
					}
					cplex.add(cplex.eq(expr, 1.0, "BSUM1"));
				}
			}
			counter = counter + 1;
		}
	
		// ADDED: enforce screening strategy to be between 0 and 1
		counter = 0;
		// For all w
		for(int t : currentTimeWindows){
			// For all possible xi
			for( PassengerDistribution xi : xiDistribution ){
				// For all flights
				for(Flight f : model.getFlights(t)){
					// For all thetas
					for(RiskCategory c : model.getAdversaryDistribution().keySet()){
						// For all teams
						for(ScreeningOperation o : model.getScreeningOperations()){
							expr = cplex.constant(0);
							for(int i : currentTimeWindows.subList(0, counter)){
								expr = cplex.sum(expr, cplex.prod(mMap.get(t).get(i).get(f).get(c).get(o), xi.get(i, f, c)));
							}
							expr = cplex.sum(expr, bMap.get(t).get(f).get(c).get(o));
							cplex.add(cplex.le(expr, 1, "LESSTHAN1_T"));
							cplex.add(cplex.ge(expr, 0, "GREATERTHAN0_T"));
							
						}
					}
				}
			}
			counter = counter + 1;
		}
		
		
	
	}
	
	private void sumDefenderScreeningThroughputRow() throws IloException{
		// ADDED: Changed from pMap to linear equation times uncertainty
		boolean firstRound = true;
		boolean lastRound = false;
		int prevt = 0;
		int counter1 = 0;		
		int counter = 0;		
		Map<ScreeningResource, Integer> screeningResources = model.getScreeningResources();
		IloNumExpr expr = cplex.constant(0);

		for(int t : currentTimeWindows){
			List<Integer> subListTimeWindows = currentTimeWindows.subList(0, counter);
			counter = counter + 1;
			for( ScreeningResource r : screeningResources.keySet() ){
				for( PassengerDistribution xi : xiDistribution ){
					expr = cplex.constant(0);
					for(Flight f : model.getFlights(t)){
						for(RiskCategory c : model.getAdversaryDistribution().keySet()){
							for(ScreeningOperation o : model.getScreeningOperations()){
								if(o.getResources().contains(r)){
									for(int i : subListTimeWindows){
										expr = cplex.sum(expr, cplex.prod( cplex.prod(mMap.get(t).get(i).get(f).get(c).get(o), xi.get(i, f, c)), xi.get(t, f, c)));
									}
									expr = cplex.sum(expr, cplex.prod( bMap.get(t).get(f).get(c).get(o), xi.get(t, f, c)) );
								}
							}
						}
					}
					
					
					if( model.hasOverflow){
						// ADDED overflow constraints for all time windows, resources
						if( firstRound ){
							// If it is the first time window, then don't include overflow from previous round
							expr = cplex.sum(expr, cplex.negative(ovMap.get(t).get(r)));
						} else if( lastRound ) {
							// If it is the last time window, then don't include overflow from this round
							expr = cplex.sum(expr, ovMap.get(prevt).get(r));
							
						} else {
							// Otherwise, include negative overflow from this round and
							// positive overflow from previous round.
							expr = cplex.sum(expr, ovMap.get(prevt).get(r));
							expr = cplex.sum(expr, cplex.negative(ovMap.get(t).get(r)));
						}
					}	
					double capacity = r.capacity() * screeningResources.get(r);	
					cplex.add(cplex.le(expr, capacity, "THRUt=" + t + "r=" + r.id() +"xi=" + xi.toString()));
				}
			}	
			counter1 = counter1 + 1;
			
			if( firstRound ){
				firstRound = false;
			}
			if( counter == (currentTimeWindows.size() - 1)){
				lastRound = true;
			}
			prevt = t;
		}
	}
	
	public void writeProblem(String filename) throws IloException{
		cplex.exportModel(filename);
	}
	
	public void writeSolution(String filename) throws IloException{
		cplex.writeSolution(filename);
	}
	
	public double getDefenderPayoff(){
		double defenderPayoff = 0.0;
		
		Map<RiskCategory, Double> adversaryDistribution = model.getAdversaryDistribution();
		
		for(RiskCategory c : model.getAdversaryDistribution().keySet()){
			defenderPayoff += defenderPayoffs.get(c) * adversaryDistribution.get(c);
		}
		
		return defenderPayoff;
	}
	
	public Map<Integer, Map<Flight, Map<RiskCategory, Map<ScreeningOperation, Double>>>> getDefenderScreeningStrategyb() throws IloException{
		Map<Integer, Map<Flight, Map<RiskCategory, Map<ScreeningOperation, Double>>>> defenderScreeningStrategyb = new HashMap<Integer, Map<Flight, Map<RiskCategory, Map<ScreeningOperation, Double>>>>();
				
		for(int t : currentTimeWindows){
			defenderScreeningStrategyb.put(t, new HashMap<Flight, Map<RiskCategory, Map<ScreeningOperation, Double>>>());
			
			for(Flight f : model.getFlights(t)){
				defenderScreeningStrategyb.get(t).put(f, new HashMap<RiskCategory, Map<ScreeningOperation, Double>>());
				
				for(RiskCategory c : f.getPassengerDistribution().keySet()){
					defenderScreeningStrategyb.get(t).get(f).put(c, new HashMap<ScreeningOperation, Double>());
					
					for(ScreeningOperation o : model.getScreeningOperations()){
						defenderScreeningStrategyb.get(t).get(f).get(c).put(o, cplex.getValue(bMap.get(t).get(f).get(c).get(o)));
					}
				}
			}
		}
		
		return defenderScreeningStrategyb;
	}
	
	public Map<Integer, Map<ScreeningResource, Double>> getDefenderOverflowStrategy() throws IloException{
		if( model.hasOverflow){
			HashMap<Integer, Map<ScreeningResource, Double>> defenderOverflowStrategy = new HashMap<Integer, Map<ScreeningResource, Double>>();
			
			List<Integer> subListTimeWindows = currentTimeWindows.subList(0, currentTimeWindows.size() - 1);
			
			for(int t : subListTimeWindows){
				defenderOverflowStrategy.put(t, new HashMap<ScreeningResource, Double>());
	
				for(ScreeningResource r : model.getScreeningResources().keySet() ){
					defenderOverflowStrategy.get(t).put(r, cplex.getValue( ovMap.get(t).get(r) ));
				}
			}
			return defenderOverflowStrategy;
		} else {
			return null;
		}
	}
	
	public Map<Integer, Map< Integer, Map<Flight, Map<RiskCategory, Map<ScreeningOperation, Double>>>>> getDefenderScreeningStrategym() throws IloException{
		Map<Integer, Map<Integer, Map<Flight, Map<RiskCategory, Map<ScreeningOperation, Double>>>>> defenderScreeningStrategym = new HashMap<Integer, Map<Integer, Map<Flight, Map<RiskCategory, Map<ScreeningOperation, Double>>>>>();
		int counter = 0;
		for(int t : currentTimeWindows){
			List<Integer> subListTimeWindows = currentTimeWindows.subList(0, counter);
			counter = counter + 1;
			defenderScreeningStrategym.put(t, new HashMap< Integer, Map<Flight, Map<RiskCategory, Map<ScreeningOperation, Double>>>>());
			for(int i : subListTimeWindows){
				defenderScreeningStrategym.get(t).put(i, new HashMap<Flight, Map<RiskCategory, Map<ScreeningOperation, Double>>>());
			
				for(Flight f : model.getFlights(t)){
					defenderScreeningStrategym.get(t).get(i).put(f, new HashMap<RiskCategory, Map<ScreeningOperation, Double>>());
				
					for(RiskCategory c : f.getPassengerDistribution().keySet()){
						defenderScreeningStrategym.get(t).get(i).get(f).put(c, new HashMap<ScreeningOperation, Double>());
					
						for(ScreeningOperation o : model.getScreeningOperations()){
							defenderScreeningStrategym.get(t).get(i).get(f).get(c).put( o, cplex.getValue( mMap.get(t).get(i).get(f).get(c).get(o)));
						}
					}
				}
			}
		}
		
		return defenderScreeningStrategym;
	}
	
	public Map<Integer, Map<Flight, Map<RiskCategory, Map<ScreeningOperation, Double>>>> getDefenderMarginalScreeningStrategyb(){
		Map<Integer, Map<Flight, Map<RiskCategory, Map<ScreeningOperation, Double>>>> defenderMarginalScreeningStrategy = new HashMap<Integer, Map<Flight, Map<RiskCategory, Map<ScreeningOperation, Double>>>>();
		
		for(int t : allTimeWindows){
			defenderMarginalScreeningStrategy.put(t, new HashMap<Flight, Map<RiskCategory, Map<ScreeningOperation, Double>>>());
			
			for(Flight f : model.getFlights(t)){
				defenderMarginalScreeningStrategy.get(t).put(f, new HashMap<RiskCategory, Map<ScreeningOperation, Double>>());
				
				for(RiskCategory c : model.getAdversaryDistribution().keySet()){
					defenderMarginalScreeningStrategy.get(t).get(f).put(c, new HashMap<ScreeningOperation, Double>());
					
					for(ScreeningOperation o : model.getScreeningOperations()){
						
						for(PassengerDistribution xi : xiDistribution){
							defenderMarginalScreeningStrategy.get(t).get(f).get(c).put(o, xi.get(t, f, c) * defenderScreeningStrategyb.get(t).get(f).get(c).get(o));
						}
					}
				}
			}
		}
		
		return defenderMarginalScreeningStrategy;
	}
	
	public void writeDefenderScreeningStrategy(String filename, double runtime) throws Exception{
		ArrayList<Integer> mSums = new ArrayList<Integer>();
		ArrayList<Integer> bSums = new ArrayList<Integer>();
		
		String[] a = filename.split(".csv");
		FileWriter fw = new FileWriter(new File(a[0] + "_b.csv"));
		
		List<RiskCategory> riskCategories = new ArrayList<RiskCategory>(model.getAdversaryDistribution().keySet());
		Collections.sort(riskCategories);
		
		List<ScreeningOperation> screeningOperations = model.getScreeningOperations();
		
		String line = "TimeWindow, Flight, RiskCategory";
		
		for(ScreeningOperation o : screeningOperations){
			line += ", " + o;
		}
		
		fw.write(line);
		for(int t : allTimeWindows){
			int bcounter = 0;
			for(Flight f : model.getFlights(t)){
				for(RiskCategory c : riskCategories){
					line = "\n" + t + ", " + f + ", " + c;
					
					for(ScreeningOperation o : screeningOperations){
						line += ", " + defenderScreeningStrategyb.get(t).get(f).get(c).get(o);
						bcounter++;
					}
					
					fw.write(line);
				}
			}
		bSums.add(bcounter);
		}
		
		fw.close();
		
		// Write slope doc
		a = filename.split(".csv");
		FileWriter fw2 = new FileWriter(new File(a[0] + "_m.csv"));
		
		riskCategories = new ArrayList<RiskCategory>(model.getAdversaryDistribution().keySet());
		Collections.sort(riskCategories);
		
		screeningOperations = model.getScreeningOperations();
		
		line = "TimeWindow, Subwindow, Flight, RiskCategory";
		
		for(ScreeningOperation o : screeningOperations){
			line += ", " + o;
		}
		
		fw2.write(line);
		
		int count = 0;
		for(int t : allTimeWindows){
			int mcounter = 0;
				List<Integer> subListTimeWindows = allTimeWindows.subList(0, count);
				count = count + 1;
				
				for(int i : subListTimeWindows ){
					for(Flight f : model.getFlights(t)){
						for(RiskCategory c : riskCategories){
							line = "\n" + t + ", " + i + ", " + f + ", " + c;
						
							for(ScreeningOperation o : screeningOperations){
								line += ", " + defenderScreeningStrategym.get(t).get(i).get(f).get(c).get(o);
								mcounter++;
							}
							fw2.write(line);
						}
					}
				}
				mSums.add(mcounter);
		}
		fw2.close();
		
		
			if( model.hasOverflow){
			// Write overflow doc
			a = filename.split(".csv");
			FileWriter fw3 = new FileWriter(new File(a[0] + "_o.csv"));
			
			List<ScreeningResource> screeningResources = new ArrayList<ScreeningResource>(model.getScreeningResources().keySet());
			
			Collections.sort( screeningResources );
			
			line = "TimeWindow";
			
			for(ScreeningResource r : screeningResources ){
				line += ", " + r;
			}
			
			fw3.write(line);
			List<Integer> subListTimeWindows = currentTimeWindows.subList(0, currentTimeWindows.size() - 1);
			for(int t : subListTimeWindows){
				line = "\n" + t;
							
				for(ScreeningResource r : screeningResources){
					line += ", " + defenderOverflowStrategy.get(t).get(r);
				}
				fw3.write(line);
			}
			fw3.close();
			}	
	}
	
	public Map<RiskCategory, Map<Integer, Map<Flight, AttackMethod>>> getAdversaryStrategies() throws IloException{
		Map<RiskCategory, Map<Integer, Map<Flight, AttackMethod>>> adversaryActionsMap = new HashMap<RiskCategory, Map<Integer, Map<Flight, AttackMethod>>>();
		
		for(RiskCategory c : model.getAdversaryDistribution().keySet()){
			double bestUtility = Double.NEGATIVE_INFINITY;
			
			for(int t : currentTimeWindows){
				for(Flight f : model.getFlights(t)){
					for(AttackMethod m : model.getAttackMethods()){
						double coverage = riskCategoryCoverage.get(t).get(c).get(f).get(m);
						
						//double utility = (coverage * f.getAttCovPayoff()) + ((1.0 - coverage)* f.getAttUncovPayoff());
						double utility = (coverage * payoffStructure.attCov(f)) + ((1.0 - coverage)* payoffStructure.attUncov(f));
					
						if(utility > bestUtility){
							bestUtility = utility;
							
							adversaryActionsMap.put(c, new HashMap<Integer, Map<Flight, AttackMethod>>());
							adversaryActionsMap.get(c).put(t, new HashMap<Flight, AttackMethod>());
							adversaryActionsMap.get(c).get(t).put(f, m);
						}
					}
				}
			}
		}
		
		return adversaryActionsMap;
	}
	
	public void writeAdversaryStrategies(String filename) throws Exception{
		FileWriter fw = new FileWriter(new File(filename));
		
		List<RiskCategory> riskCategories = new ArrayList<RiskCategory>(model.getAdversaryDistribution().keySet());
		Collections.sort(riskCategories);
		
		String line = "RiskCategory, TimeWindow, Flight, AttackMethod";
		
		fw.write(line);
		
		for(RiskCategory c : riskCategories){
			for(int t : adversaryStrategies.get(c).keySet()){
				for(Flight f : adversaryStrategies.get(c).get(t).keySet()){
					line = "\n" + c + ", " + t + ", " + f + ", " + adversaryStrategies.get(c).get(t).get(f);
						
					fw.write(line);
				}
			}
		}
		
		fw.close();
	}
	
	public Map<RiskCategory, Double> getAdversaryPayoffs() throws IloException{
		Map<RiskCategory, Double> adversaryPayoffsMap = new HashMap<RiskCategory, Double>();
		
		if(zeroSum){
			for(RiskCategory c : model.getAdversaryDistribution().keySet()){
				adversaryPayoffsMap.put(c, -1 * cplex.getValue(dMap.get(c)));
			}
		}
		
		return adversaryPayoffsMap;
	}
	
	public void writeAdversaryPayoffs(String filename) throws Exception{
		FileWriter fw = new FileWriter(new File(filename));
	
		List<RiskCategory> riskCategories = new ArrayList<RiskCategory>(model.getAdversaryDistribution().keySet());
		Collections.sort(riskCategories);
		
		fw.write("RiskCategory, Payoff");
		
		for(RiskCategory c : riskCategories){
			fw.write("\n" + c + ", " + adversaryPayoffs.get(c));
		}
		
		fw.close();
	}
	
	public Map<RiskCategory, Double> getDefenderPayoffs() throws IloException{
		Map<RiskCategory, Double> defenderPayoffsMap = new HashMap<RiskCategory, Double>();
		
		for(RiskCategory c : model.getAdversaryDistribution().keySet()){
			defenderPayoffsMap.put(c, cplex.getValue(dMap.get(c)));
		}
		
		return defenderPayoffsMap;
	}
	
	public void writeDefenderPayoffs(String filename) throws Exception{
		FileWriter fw = new FileWriter(new File(filename));
		
		Map<RiskCategory, Double> adversaryDistribution = model.getAdversaryDistribution();
	
		List<RiskCategory> riskCategories = new ArrayList<RiskCategory>(adversaryDistribution.keySet());
		Collections.sort(riskCategories);
		
		fw.write("RiskCategory, Payoff, Probability, Utility");
		
		double totalUtility = 0.0;
		
		for(RiskCategory c : riskCategories){
			double payoff = defenderPayoffs.get(c);
			double probability = adversaryDistribution.get(c);
			double utility = payoff * probability;
			
			fw.write("\n" + c + ", " + payoff + ", " + probability + ", " + utility);
			
			totalUtility += utility;
		}
		
		fw.write("\n,, Defender Utility:," + totalUtility);
		
		fw.close();
	}
	
	private Map<Integer, Map<RiskCategory, Map<Flight, Map<AttackMethod, Double>>>> getRiskCategoryCoverage() throws IloException{
		Map<Integer, Map<RiskCategory, Map<Flight, Map<AttackMethod, Double>>>> riskCategoryCoverageMap = new HashMap<Integer, Map<RiskCategory, Map<Flight, Map<AttackMethod, Double>>>>();
	
		for(int t : currentTimeWindows){
			riskCategoryCoverageMap.put(t, new HashMap<RiskCategory, Map<Flight, Map<AttackMethod, Double>>>());
			
			for(RiskCategory c : model.getAdversaryDistribution().keySet()){
				riskCategoryCoverageMap.get(t).put(c, new HashMap<Flight, Map<AttackMethod, Double>>());
				
				for(Flight f : model.getFlights(t)){
					riskCategoryCoverageMap.get(t).get(c).put(f, new HashMap<AttackMethod, Double>());
					
					for(AttackMethod m : model.getAttackMethods()){
						riskCategoryCoverageMap.get(t).get(c).get(f).put(m, cplex.getValue(bMap.get(t).get(c).get(f).get(m)));
					}
				}
			}
		}
		
		return riskCategoryCoverageMap;
	}
	
	public double calculateDefenderPayoff(){
		return calculateDefenderPayoff(defenderScreeningStrategyb);
	}
	
	public double calculateDefenderPayoff(Map<Integer, Map<Flight, Map<RiskCategory, Map<ScreeningOperation, Double>>>> marginalStrategy){
		Map<RiskCategory, Double> adversaryDistribution = model.getAdversaryDistribution();
		
		double defenderUtility = 0.0;
		
		for(RiskCategory c : adversaryDistribution.keySet()){
			double worstUtility = Double.POSITIVE_INFINITY;
			
			for(int t : allTimeWindows){
				for(Flight f : model.getFlights(t)){
					for(AttackMethod m : model.getAttackMethods()){
						double coverage = 0.0;
						
						for(ScreeningOperation o : model.getScreeningOperations()){
							coverage += marginalStrategy.get(t).get(f).get(c).get(o) * o.effectiveness(c, m);
						}
						
						double utility = (coverage * payoffStructure.defCov(f)) + ((1.0 - coverage)* payoffStructure.defUncov(f));
					
						if(utility < worstUtility){
							worstUtility = utility;
						}
					}
				}
			}
			
			defenderUtility += adversaryDistribution.get(c) * worstUtility;
		}
		
		return defenderUtility;
	}
	
	public Map<Integer, Map<RiskCategory, Map<Flight, Map<AttackMethod, Double>>>> calculateRiskCategoryCoverage() throws IloException{
		Map<Integer, Map<RiskCategory, Map<Flight, Map<AttackMethod, Double>>>> riskCategoryCoverageMap = new HashMap<Integer, Map<RiskCategory, Map<Flight, Map<AttackMethod, Double>>>>();
	
		for(int t : currentTimeWindows){
			riskCategoryCoverageMap.put(t, new HashMap<RiskCategory, Map<Flight, Map<AttackMethod, Double>>>());
			
			for(RiskCategory c : model.getAdversaryDistribution().keySet()){
				riskCategoryCoverageMap.get(t).put(c, new HashMap<Flight, Map<AttackMethod, Double>>());
				
				for(Flight f : model.getFlights(t)){
					riskCategoryCoverageMap.get(t).get(c).put(f, new HashMap<AttackMethod, Double>());
					
					for(AttackMethod m : model.getAttackMethods()){
						double probability = 0.0;
						
						for(ScreeningOperation o : model.getScreeningOperations()){
							probability += cplex.getValue(bMap.get(t).get(f).get(c).get(o)) * o.effectiveness(c, m);
						}
						
						if(probability > 1.0){
							probability = 1.0;
						}
						
						riskCategoryCoverageMap.get(t).get(c).get(f).put(m, probability);
					}
				}
			}
		}
		
		return riskCategoryCoverageMap;
	}
	
	public void writeRiskCategoryCoverage(String filename) throws Exception{
		FileWriter fw = new FileWriter(new File(filename));
	
		List<AttackMethod> attackMethods = model.getAttackMethods();
		
		List<RiskCategory> riskCategories = new ArrayList<RiskCategory>(model.getAdversaryDistribution().keySet());
		Collections.sort(riskCategories);
		
		String line = "RiskCategory, TimeWindow, Flight";
		
		for(AttackMethod m : attackMethods){
			line += ", " + m + "_coverage, " + m + "_payoff, " + m + "_utility";
		}
		
		fw.write(line);
		
		for(RiskCategory c : riskCategories){
			for(int t : allTimeWindows){
				for(Flight f : model.getFlights(t)){
					line = "\n" + c + ", " + t + ", " + f;
					
					for(AttackMethod m : attackMethods){
						double coverage = riskCategoryCoverage.get(t).get(c).get(f).get(m);
						double payoff = coverage * payoffStructure.attCov(f) + ((1.0 - coverage) * payoffStructure.attUncov(f));
						double utility = payoff * model.getAdversaryDistribution().get(c);
						
						line += ", " + coverage + ", " + payoff + ", " + utility;
					}
					
					fw.write(line);
				}
			}
		}
		
		fw.close();
	}
	
	public void writeTemporalPassengerDistribution(String filename) throws Exception{
		int counter = 0;
		// for( PassengerDistribution xi: xiDistribution ){
		for( int num = 0; num < Math.min( xiDistribution.size(), 20); num++){	
			PassengerDistribution xi = xiDistribution.get(num);
			counter = counter + 1;
			String[] a = filename.split(".csv");
			FileWriter fw = new FileWriter(new File(a[0] + "xi" + counter + ".csv"));
			
			List<RiskCategory> riskCategories = new ArrayList<RiskCategory>(model.getAdversaryDistribution().keySet());
			Collections.sort(riskCategories);
			
			String line = "TimeWindow, Flight";
			
			for(RiskCategory c : riskCategories){
				line += ", " + c;
			}
			
			line += ", " + "TOTAL";
			fw.write(line);
			
			for(int t : allTimeWindows){
				for(Flight f : model.getFlights(t)){
					line = "\n" + t + ", " + f;
					
					int totalPassengers = 0;
					
					for(RiskCategory c : riskCategories){
						
						line += ", " + xi.get(t, f, c);
						
						totalPassengers += xi.get(t, f, c);
					}
					
					line += ", " + totalPassengers;
						
					fw.write(line);
				}
			}
			
			fw.close();
	
		}
	}

	public void writeNumberDecisionVariables(String fname, double runtime) throws IOException, IloException {
		System.out.println( fname );
		FileWriter fw = new FileWriter(new File(fname));
		
		fw.write("Seed, " + model.seed + "\n");
		fw.write("Decision Rule, " + model.decisionRule + "\n");
		fw.write("Epsilon, " + model.eps + "\n");
		fw.write("Beta, " + model.beta + "\n");
		fw.write("Number Decision Variables, " + model.getDecVariables() + "\n");
		fw.write("Number of Samples, " + xiDistribution.size() + "\n");
		fw.write("Number of Constraints, " + cplex.getNrows() + "\n");
		fw.write("Amount Uncertainty, " + model.uncertain + "\n");
		fw.write("Experimental Epsilon, " + this.expEpsilon  + "\n");
		fw.write("Objective Value, " + cplex.getObjValue() + "\n");
		fw.write("\n");
		fw.write("Total Runtime, " + runtime + "\n");
		fw.write("Solver Runtime, " + this.solverTime + "\n");
		fw.write("\n");
		fw.write("Number of flights, " + model.getFlights().size() + "\n");
		fw.write("Number of risk levels, " + model.getAdversaryDistribution().keySet().size() + "\n");
		fw.write("Number of teams, " + model.getScreeningOperations().size() + "\n");
		fw.write("Number of time windows, " + model.getTimeWindows().size() + "\n");
		
		System.out.println("Seed, " + model.seed + "\n");
		System.out.println("Decision Rule, " + model.decisionRule + "\n");
		System.out.println("Amount Uncertainty, " + model.uncertain + "\n");
		System.out.println("Number of flights, " + model.getFlights().size() + "\n");
		System.out.println("Epsilon, " + model.eps + "\n");
		System.out.println("Beta, " + model.beta + "\n");
		System.out.println("Number Decision Variables, " + model.getDecVariables() + "\n");
		System.out.println("Number of Samples, " + xiDistribution.size() + "\n");
		System.out.println("Number of Constraints, " + cplex.getNrows() + "\n");
		System.out.println("Experimental Epsilon, " + this.expEpsilon  + "\n");
		System.out.println("Objective Value, " + cplex.getObjValue() + "\n");
		System.out.println("\n");
		System.out.println("Runtime, " + runtime + "\n");
		
		fw.close();
		
	}

	public void cleanUp() {
		// TODO Auto-generated method stub
		cplex.end();
	}
	
	public double calculateViolationProbability() {
		int numViolated = 0;
		System.out.println( "Starting to calculate the violation probability...");
		for( PassengerDistribution v : model.getViolDistribution() ){
			boolean isViolated = false;
			
			// check utility violation
			Collections.sort(currentTimeWindows);
			int counter = 0;
			
			for(int t : currentTimeWindows){
				List<Integer> subListTimeWindows = currentTimeWindows.subList(0, counter);
				counter = counter + 1;
					for(RiskCategory c : model.getAdversaryDistribution().keySet()){
						for(Flight f : model.getFlights(t)){
							for(AttackMethod m : model.getAttackMethods()){
								
								// ADDED
								
								double val = 0;
								for(ScreeningOperation o : model.getScreeningOperations()){
									double val2 = 0;
									for(int i : subListTimeWindows){
										// sum ( m * xi ) over all prev time window
										val2 = val2 + defenderScreeningStrategym.get(t).get(i).get(f).get(c).get(o) * v.get(i, f, c);
									}
									
									// Plus b_{f,c,team}^{current window}
									val2 = val2 + defenderScreeningStrategyb.get(t).get(f).get(c).get(o);
									
									// Times effectiveness
									// QUESTION: why is effectiveness negative?
									val2 = val2 * o.effectiveness(c, m);
									System.out.println(o + " " + o.effectiveness(c, m));

									// This product is added to the sum over all teams
									val = val + val2;
								}
								
								// Multiply by payoff values and add the evaluated payoff value
								val = val * (payoffStructure.defUncov(f) - payoffStructure.defCov(f)) + defenderPayoffs.get(c);
								
								// If this value is greater, then this realization violates.
								System.out.println(val + " < " + payoffStructure.defUncov(f));
								if ( val > payoffStructure.defUncov(f) + 0.00000001){
									System.out.println("VIOLATION");
									isViolated = true;
								}
								
							}
						}
					}
			}
			
			
			
			
			// check is a probability violation
			Map<ScreeningResource, Integer> screeningResources = model.getScreeningResources();
			counter = 0;
			// For all w
			for(int t : currentTimeWindows){
				List<Integer> subListTimeWindows = currentTimeWindows.subList(0, counter);
				counter = counter + 1;
					// For all flights
					for(Flight f : model.getFlights(t)){
						// For all thetas
						for(RiskCategory c : model.getAdversaryDistribution().keySet()){
							// For all teams
							for(ScreeningOperation o : model.getScreeningOperations()){
								
								double val = 0;
								for(int i : subListTimeWindows){
									// m*xi for all previous time windows
									val = val + defenderScreeningStrategym.get(t).get(i).get(f).get(c).get(o) * v.get(i, f, c);
								}
								// plus the current b
								val = val + defenderScreeningStrategyb.get(t).get(f).get(c).get(o);
								// if this value is not a real probability...
								System.out.println("0 < "+ val + " < 1");
								if( (val > 1.000001) || ( val < -0.00000001) ){
									System.out.println("VIOLATION");
									isViolated = true;
								}
							}
						}
					}
				}
			
			
			
			
			// check throughput violation
			counter = 0;		
			screeningResources = model.getScreeningResources();

			for(int t : currentTimeWindows){
				List<Integer> subListTimeWindows = currentTimeWindows.subList(0, counter);
				counter = counter + 1;
				for( ScreeningResource r : screeningResources.keySet() ){
						double val = 0;
						for(Flight f : model.getFlights(t)){
							for(RiskCategory c : model.getAdversaryDistribution().keySet()){
								for(ScreeningOperation o : model.getScreeningOperations()){
									if(o.getResources().contains(r)){
										for(int i : subListTimeWindows){
											val = val + defenderScreeningStrategym.get(t).get(i).get(f).get(c).get(o) * v.get(i, f, c) * v.get(t, f, c);
										}
										val = val + defenderScreeningStrategyb.get(t).get(f).get(c).get(o) * v.get(t, f, c);
									}
								}
							}
						}
						double capacity = r.capacity() * screeningResources.get(r);
						
						System.out.println(val + " < " + capacity);
						// note: I added this small error here
						if( val > capacity + 0.000000001 ){
							System.out.println("VIOLATION");
							isViolated = true;
						}
			
					}
				}

			if( isViolated ){
				numViolated++;
			}
		
		}
		this.expEpsilon = ( numViolated* 1.0 ) / ( 1.0 * model.getViolDistribution().size() );
		System.out.println( "Finished calculating the violation probability...");
		return ( numViolated* 1.0 ) / ( 1.0 * model.getViolDistribution().size() );
	}
	
	
}