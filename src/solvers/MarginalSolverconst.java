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
import ilog.concert.IloException;
import ilog.concert.IloNumExpr;
import ilog.concert.IloNumVar;
import ilog.concert.IloNumVarType;
import ilog.concert.IloRange;
import ilog.cplex.IloCplex;

public class MarginalSolverconst{
	private DARMSModel model;
	
	private IloCplex cplex;
	
	private Map<RiskCategory, IloNumVar> dMap;
	private Map<Integer, Map<RiskCategory, Map<Flight, Map<AttackMethod, IloNumVar>>>> aMap;
	
	private Map<Integer, HashMap<ScreeningResource, IloNumVar>> ovMap;

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
	
	private static final int MM = 999999999;
	
	private List<Integer> allTimeWindows;
	private List<Integer> currentTimeWindows;
	
	private List<PassengerDistribution> xiDistribution;
	
	private PayoffStructure payoffStructure;
	
	private boolean zeroSum;
	private boolean decomposed;
	private boolean flightByFlight;
	private boolean naive;
	
	public MarginalSolverconst(DARMSModel model, boolean zeroSum, boolean decomposed, boolean flightByFlight, boolean naive) throws Exception{
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
	
	public MarginalSolverconst(DARMSModel model, PassengerDistribution passengerDistribution, boolean zeroSum, boolean decomposed, boolean flightByFlight, boolean naive) throws Exception{
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
	
	public MarginalSolverconst(DARMSModel model, PayoffStructure payoffStructure, boolean zeroSum, boolean decomposed, boolean flightByFlight, boolean naive) throws Exception{
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
		bMap = new HashMap<Integer, Map<Flight, Map<RiskCategory, Map<ScreeningOperation, IloNumVar>>>>();
		// END ADD
		
		cplex = new IloCplex();
		cplex.setName("DARMS");
		cplex.setParam(IloCplex.IntParam.RootAlg, IloCplex.Algorithm.Barrier);
		cplex.setParam(IloCplex.IntParam.BarCrossAlg, IloCplex.Algorithm.None);
		cplex.setOut(null);
		
		this.currentTimeWindows = timeWindows;
		
		initVars();
		initConstraints();
		initObjective();
	}
	
	private void initVars() throws IloException{
		List<IloNumVar> varList = new ArrayList<IloNumVar>();
			
		// ADDED: bMap
			
		for(int t : currentTimeWindows){
			bMap.put(t, new HashMap<Flight, Map<RiskCategory, Map<ScreeningOperation, IloNumVar>>>());
			
			for(Flight f : model.getFlights(t)){
				bMap.get(t).put(f, new HashMap<RiskCategory, Map<ScreeningOperation, IloNumVar>>());
				
				for(RiskCategory c : model.getAdversaryDistribution().keySet()){
					bMap.get(t).get(f).put(c, new HashMap<ScreeningOperation, IloNumVar>());
					
					for(ScreeningOperation o : model.getScreeningOperations()){
						IloNumVar var = cplex.numVar(-MM, MM, IloNumVarType.Float, "b_t" + t + "_f" +  f.id() + "_c" + c.toString() + "_o" + o.getID());
					
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
			IloNumVar var1 = cplex.numVar(-MM, MM, IloNumVarType.Float, "d_c" + c.toString());
				
			dMap.put(c, var1);
			
			varList.add(var1);
		}
		
		IloNumVar[] v = new IloNumVar[varList.size()];

		cplex.add(varList.toArray(v));
	}
	
	private void initConstraints() throws IloException{
		constraints = new ArrayList<IloRange>();
		
		sumDefenderScreeningActionRow();
		sumDefenderScreeningThroughputRow();
		sumDefenderCoverageRow();
		
		IloRange[] c = new IloRange[constraints.size()];

		cplex.add(constraints.toArray(c));
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
				
				writeProblem("DARMS.lp");
				
				cplex.solve();
				
				if(!cplex.isPrimalFeasible()){
					throw new Exception("Infeasible. Capacity constraints exceeded. Time Window: " + t);
				}

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
			writeProblem("DARMS.lp");
			cplex.solve();
			
			writeSolution("DARMS2.sol");
			
			if(!cplex.isPrimalFeasible()){
				writeProblem("Infeasible.lp");
				System.out.println( "This is infeasible");
				//writeProblem("Infeasible.sol.txt");
				throw new Exception("Infeasible. Capacity constraints exceeded.");
			}
			
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
		for(int t : currentTimeWindows){
			List<Integer> subListTimeWindows = currentTimeWindows.subList(0, counter);
			counter = counter + 1;
			for( PassengerDistribution xi : xiDistribution ){
				for(RiskCategory c : model.getAdversaryDistribution().keySet()){
					for(Flight f : model.getFlights(t)){
						for(AttackMethod m : model.getAttackMethods()){
							
							// ADDED
							
							IloNumExpr expr = cplex.constant(0);
							for(ScreeningOperation o : model.getScreeningOperations()){
								IloNumExpr expr2 = cplex.constant(0);
								expr2 = cplex.sum(expr2, bMap.get(t).get(f).get(c).get(o) );
								// Times effectiveness
								// QUESTION: why is effectiveness negative?
								
								expr2 = cplex.prod(expr2, o.effectiveness(c, m));
								// This product is added to the sum over all teams
								expr = cplex.sum(expr, expr2);
							}
							
							expr = cplex.sum(dMap.get(c), cplex.prod(expr, payoffStructure.defUncov(f) - payoffStructure.defCov(f)));
							//constraints.add(cplex.le(expr, f.getDefUncovPayoff(), "DC" + t + "C" + c.id() + "F" + f.id() + "M" + m.id()));
							constraints.add(cplex.le(expr, payoffStructure.defUncov(f), "DEFCOVt=" + t + "c=" + c.id() + "f=" + f.id() + "m=" + m.id() +"xi=" + xi.toString() ));
						}
					}
				}
			}
		}
	}
	
//	private void setZeroSumDefenderPayoffRow() throws IloException{
//		for(int t : currentTimeWindows){
//			for(RiskCategory c : model.getAdversaryDistribution().keySet()){
//				for(Flight f : model.getFlights(t)){
//					for(AttackMethod m : model.getAttackMethods()){
//						//IloNumExpr expr = cplex.sum(dMap.get(c), cplex.prod(xMap.get(t).get(c).get(f).get(m), f.getDefUncovPayoff() - f.getDefCovPayoff()));
//						IloNumExpr expr = cplex.sum(dMap.get(c), cplex.prod(xMap.get(t).get(c).get(f).get(m), payoffStructure.defUncov(f) - payoffStructure.defCov(f)));
//						
//						//constraints.add(cplex.le(expr, f.getDefUncovPayoff(), "DC" + t + "C" + c.id() + "F" + f.id() + "M" + m.id()));
//						constraints.add(cplex.le(expr, payoffStructure.defUncov(f), "DC" + t + "C" + c.id() + "F" + f.id() + "M" + m.id()));
//					}
//				}
//			}
//		}
//	}
	
//	private void setGeneralSumDefenderPayoffRow() throws IloException{
//		for(int t : currentTimeWindows){
//			for(RiskCategory c : model.getAdversaryDistribution().keySet()){
//				for(Flight f : model.getFlights(t)){
//					for(AttackMethod m : model.getAttackMethods()){
//						//IloNumExpr expr = cplex.sum(dMap.get(c), cplex.prod(xMap.get(t).get(c).get(f).get(m), f.getDefUncovPayoff() - f.getDefCovPayoff()));
//						IloNumExpr expr = cplex.sum(dMap.get(c), cplex.prod(xMap.get(t).get(c).get(f).get(m), payoffStructure.defUncov(f) - payoffStructure.defCov(f)));
//						
//						expr = cplex.sum(expr, cplex.prod(aMap.get(t).get(c).get(f).get(m), MM));
//						
//						//constraints.add(cplex.le(expr, MM + f.getDefUncovPayoff(), "DC" + t + "C" + c.id() + "F" + f.id() + "M" + m.id()));
//						constraints.add(cplex.le(expr, MM + payoffStructure.defUncov(f), "DC" + t + "C" + c.id() + "F" + f.id() + "M" + m.id()));
//					}
//				}
//			}
//		}
//	}

//	private void setStaticScreening() throws IloException{
//		for(int t : currentTimeWindows){
//			for(RiskCategory c : model.getAdversaryDistribution().keySet()){
//				for(ScreeningOperation o : model.getScreeningOperations()){
//					for(Flight f1 : model.getFlights(t)){
//						IloNumExpr expr1 = sMap.get(t).get(f1).get(c).get(o);
//						
//						for(Flight f2 : model.getFlights(t)){
//							if(f2.id() - f1.id() == 1){
//								IloNumExpr expr2 = cplex.prod(-1.0, sMap.get(t).get(f2).get(c).get(o));
//								
//								IloNumExpr expr = cplex.sum(expr1, expr2);
//								
//								constraints.add(cplex.eq(expr, 0.0, "T" + t + "C" + c.id() + "O" + o.getID() + "F" + f1.id() + "F" + f2.id()));
//							}
//						}
//					}
//				}
//			}
//		}
//	}
	
//	private void setNaiveScreening() throws IloException{
//		for(int t : currentTimeWindows){
//			for(RiskCategory c : model.getAdversaryDistribution().keySet()){
//				for(ScreeningOperation o : model.getScreeningOperations()){
//					for(Flight f1 : model.getFlights(t)){
//						IloNumExpr expr1 = sMap.get(t).get(f1).get(c).get(o);
//						
//						for(Flight f2 : model.getFlights(t)){
//							if(f2.id() - f1.id() == 1){
//								IloNumExpr expr2 = cplex.prod(-1.0, sMap.get(t).get(f2).get(c).get(o));
//								
//								IloNumExpr expr = cplex.sum(expr1, expr2);
//								
//								constraints.add(cplex.eq(expr, 0.0, "T" + t + "C" + c.id() + "O" + o.getID() + "F" + f1.id() + "F" + f2.id()));
//							}
//						}
//					}
//				}
//			}
//		}
//		
//		for(int t : currentTimeWindows){
//			for(Flight f : model.getFlights(t)){
//				for(ScreeningOperation o : model.getScreeningOperations()){
//					for(RiskCategory c1 : model.getAdversaryDistribution().keySet()){
//						IloNumExpr expr1 = sMap.get(t).get(f).get(c1).get(o);
//						
//						for(RiskCategory c2 : model.getAdversaryDistribution().keySet()){
//							if(c2.id() - c1.id() == 1){
//								IloNumExpr expr2 = cplex.prod(-1.0, sMap.get(t).get(f).get(c2).get(o));
//								
//								IloNumExpr expr = cplex.sum(expr1, expr2);
//								
//								constraints.add(cplex.eq(expr, 0.0, "T" + t + "F" + f.id() + "O" + o.getID() + "C" + c1.id() + "C" + c2.id()));
//							}
//						}
//					}
//				}
//			}
//		}
//	}
	
	private void sumDefenderScreeningActionRow() throws IloException{
		// ADDED: enforce every possible sampled screening strategy sum to 1 across all the teams
		int counter = 0;
		// For all w
		for(int t : currentTimeWindows){
			List<Integer> subListTimeWindows = currentTimeWindows.subList(0, counter);
			counter = counter + 1;
			// For all possible xi
			for( PassengerDistribution xi : xiDistribution ){
				// For all flights
				for(Flight f : model.getFlights(t)){
					// For all thetas
					for(RiskCategory c : model.getAdversaryDistribution().keySet()){
						// For all teams
						IloNumExpr expr = cplex.constant(0);
						for(ScreeningOperation o : model.getScreeningOperations()){
							expr = cplex.sum(expr, bMap.get(t).get(f).get(c).get(o));
						}
						constraints.add(cplex.eq(expr, 1, "ALLOPSSUMONE_T" + t + "_XI" + xi.toString() + "_C" + c.id() + "_F" + f.id()));
					}
				}
			}
		}
		
	
		// ADDED: enforce screening strategy to be between 0 and 1
		counter = 0;
		// For all w
		for(int t : currentTimeWindows){
			List<Integer> subListTimeWindows = currentTimeWindows.subList(0, counter);
			counter = counter + 1;
			// For all possible xi
			for( PassengerDistribution xi : xiDistribution ){
				// For all flights
				for(Flight f : model.getFlights(t)){
					// For all thetas
					for(RiskCategory c : model.getAdversaryDistribution().keySet()){
						// For all teams
						for(ScreeningOperation o : model.getScreeningOperations()){
							IloNumExpr expr = cplex.constant(0);
							expr = cplex.sum(expr, bMap.get(t).get(f).get(c).get(o));
							constraints.add(cplex.le(expr, 1, "LESSTHAN1_T" + t + "_XI" + xi.toString() + "_C" + c.id() + "_F" + f.id() + "_O" + o.toString()));
							constraints.add(cplex.ge(expr, -1, "GREATERTHAN0_T" + t +"_XI" + xi.toString() + "_C" + c.id() + "_F" + f.id() + "_O" + o.toString()));
							
						}
					}
				}
			}
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

		for(int t : currentTimeWindows){
			List<Integer> subListTimeWindows = currentTimeWindows.subList(0, counter);
			counter = counter + 1;
			for( ScreeningResource r : screeningResources.keySet() ){
				for( PassengerDistribution xi : xiDistribution ){
					IloNumExpr expr = cplex.constant(0);
					for(Flight f : model.getFlights(t)){
						for(RiskCategory c : model.getAdversaryDistribution().keySet()){
							for(ScreeningOperation o : model.getScreeningOperations()){
								if(o.getResources().contains(r)){
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
					constraints.add(cplex.le(expr, capacity, "THRUt=" + t + "r=" + r.id() +"xi=" + xi.toString()));
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
		// Write Stats doc
		
		FileWriter fw4 = new FileWriter(new File("NumberDecisionVariables.csv"));
		line = "Window Number, m Variables, b Variables, Decision Variables\n";
		
		int totalDec = 0;
		
		fw4.write(line);
		
		int count = 0;
		for( int t: allTimeWindows ){
			
			if( count == 0){
				int firstRound = bSums.get(0) + model.getScreeningResources().keySet().size();
				int firstBasis = bSums.get(0);
				totalDec +=firstRound;
				line = "1, " + ", " + bSums.get(0) + ", " + firstRound + "\n";
				fw4.write(line);
				count++;
			} else if( count == ( allTimeWindows.size() - 1 )){
				int lastRound = bSums.get(count) + model.getScreeningResources().keySet().size();
				int lastBasis = bSums.get(count);
				totalDec +=lastRound;
				line = (count + 1) + ", " + bSums.get(count) + ", " + lastRound + "\n";
				fw4.write(line);
				count++;
			} else {	
				int currentRound = bSums.get(count) + ( model.getScreeningResources().keySet().size() * 2 );
				totalDec +=currentRound;
				int currentBasis = bSums.get(count);
				line = (count + 1) + ", " + bSums.get(count) + ", " + currentRound + "\n";
				fw4.write(line);
				count++;
			}
		}
		
		fw4.write("\n");
		fw4.write("");
		fw4.write("Total # Decision Variables, " + totalDec + "\n");
		fw4.write("Sample Number, " + xiDistribution.size() + "\n");
		fw4.write("\n");
		fw4.write("Runtime, " + runtime + "\n");
		
		fw4.close();
		
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
		for( int num = 0; num < 2; num++){	
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

	public void writeNumberDecisionVariables(String fname, double runtime) throws IOException {
		FileWriter fw = new FileWriter(new File(fname));
		String line = "Window Number, Decision Variables, Basis Size\n";
		
		int totalDec = 0;
		
		fw.write(line);
		int firstRound = model.getFlights().size() * 1 + model.getScreeningResources().keySet().size();
		int firstBasis = model.getFlights().size() * 1;
		totalDec +=firstRound;
		line = "1, " + firstRound + ", " + firstBasis + "\n";
		fw.write(line);
		
		
		for( int j = 1; j < model.getTimeWindows().size() - 1; j++ ){
			int currentRound = model.getFlights().size() * (j + 1) + ( model.getScreeningResources().keySet().size() * 2 );
			totalDec +=currentRound;
			int currentBasis = model.getFlights().size() * (j + 1);
			line = (j + 1) + ", " + currentRound + ", " + currentBasis + "\n";
			fw.write(line);
		}
		
		int lastRound = model.getFlights().size() * model.getTimeWindows().size() + model.getScreeningResources().keySet().size();
		int lastBasis = model.getFlights().size() * model.getTimeWindows().size();
		
		totalDec +=lastRound;
		
		line = model.getTimeWindows().size() + ", " + lastRound + ", " + lastBasis + "\n";
		fw.write(line);
		fw.write("\n");
		fw.write("");
		fw.write("Total # Decision Variables, " + totalDec + "\n");
		fw.write("Sample Number, " + xiDistribution.size() + "\n");
		fw.write("\n");
		fw.write("Runtime, " + runtime + "\n");
		
		fw.close();
		
	}

	public void cleanUp() {
		// TODO Auto-generated method stub
		cplex.end();
	}
	
	
}