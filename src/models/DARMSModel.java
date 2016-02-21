package models;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.util.ArithmeticUtils;

import com.google.common.math.BigIntegerMath;
import com.google.common.math.LongMath;

import utilities.DARMSInstanceGenerator;

public class DARMSModel {
	private List<Flight> flights;
	private Map<RiskCategory, Double> adversaryDistribution;
	private List<ScreeningOperation> screeningOperations;
	private Map<ScreeningResource, Integer> screeningResources;
	private Map<PostScreeningResource, Integer> postScreeningResources;
	private List<AttackMethod> attackMethods;
	private ResourceFines  resourceFines;
	private boolean flightByFlight;
	private int shiftStartTime;
	private int shiftDuration;
	private int timeGranularity;
	public int numberTrials;

	
	private List<Integer> timeWindows;
	
	private Map<Integer, List<Flight>> flightMap;
	
	public static final double EPSILON = 1e-6;
	
	private PayoffStructure payoffStructure;
	private ArrayList<PassengerDistribution> xiDistribution;
	private ArrayList<PassengerDistribution> violDistribution;
	private int uncertain;
	private int numberSamples;
	public boolean aggregate;
	public boolean hasOverflow;
	public int seed;
	public String decisionRule;
	private int numViolProb;
	public double beta;
	public double eps;
	public int decVariables;

	public DARMSModel(List<Flight> flights, 
			Map<RiskCategory, Double> adversaryDistribution,
			List<AttackMethod> attackMethods,
			List<ScreeningOperation> screeningOperations,
			Map<ScreeningResource, Integer> screeningResources,
			Map<PostScreeningResource, Integer> postScreeningResources,
			boolean flightByFlight,
			int shiftStartTime,
			int shiftDuration,
			int timeGranularity,
			String fineDist,
			double fineMin,
			double fineMax,
			int numberTests,
			int thisTest,
			int uncert, double epsilon, double beta, boolean overflow, boolean aggregate, int numberSamples2, String decisionRule2, Integer seed2) throws Exception{
		this.flights = flights;
		this.adversaryDistribution = adversaryDistribution;
		this.attackMethods = attackMethods;
		this.screeningOperations = screeningOperations;
		this.screeningResources = screeningResources;
		this.postScreeningResources = postScreeningResources;
		this.flightByFlight = flightByFlight;
		this.shiftStartTime = shiftStartTime;
		this.shiftDuration = shiftDuration;
		this.timeGranularity = timeGranularity;
		this.numberTrials = numberTests;
		this.uncertain = uncert;
		
		if( seed2 == 0 ){
			Random seedDice = new Random();
			this.seed = seedDice.nextInt();
		} else {
			this.seed = seed2;
		}
		
		if( decisionRule2 == null ){
			this.decisionRule = "linear";
		} else {
			this.decisionRule = decisionRule2;
		}
		
		this.numViolProb = 1000;
		
		this.eps = epsilon;
		this.beta = beta;
		
		if( numberSamples2==0){
			this.numberSamples = calcNumberSamples(epsilon, beta);
		} else {
			this.numberSamples = numberSamples2;
		}
		this.hasOverflow = overflow;
		this.aggregate = aggregate;
		
		System.out.println("Number of samples: " + this.numberSamples );
		
		for(ScreeningOperation o : this.screeningOperations){
			for(ScreeningResource r : o.getResources()){
				if(!this.screeningResources.containsKey(r)){
					this.screeningResources.put(r, 0);
				}
			}
		}
		
		timeWindows = new ArrayList<Integer>(); 
		
		for(int i = 0; i < shiftDuration / timeGranularity; i++){
			timeWindows.add(shiftStartTime + (timeGranularity * i));
		}
		
		this.setResourceFines( fineDist, fineMin, fineMax, numberTests, thisTest );
	}
	
	public void calculateTemporalPassengerDistributions(){
		
		Random dice = new Random( this.seed );
		
		NormalDistribution distribution = null;
		flightMap = new HashMap<Integer, List<Flight>>();
		System.out.print("Uncertain parameter: " + this.uncertain);
		for(Flight f : flights){
			Map<RiskCategory, Integer> passengerDistribution = f.getPassengerDistribution();
			
			List<Map<Integer, Map<RiskCategory, Integer>>> temporalPassengerDistributionList = new ArrayList<Map<Integer, Map<RiskCategory, Integer>>>();
			for( int count = 0; count < numberSamples + numViolProb + 1; count++ ){
				Map<Integer, Map<RiskCategory, Integer>> temporalPassengerDistribution = new HashMap<Integer, Map<RiskCategory, Integer>>();
				
				ArrayList<RiskCategory> passList = new ArrayList<RiskCategory>( passengerDistribution.keySet());
				Collections.sort( passList );
				
				for(RiskCategory c : passList){
					
					int a = -190 + dice.nextInt(this.uncertain*2 + 1) - this.uncertain;
					int b =  50 + dice.nextInt(this.uncertain*2 + 1) - this.uncertain;
					int d = -190 + dice.nextInt(this.uncertain*2 + 1) - this.uncertain;
					int e =  50 + dice.nextInt(this.uncertain*2 + 1) - this.uncertain;
					
					// System.out.println(a+", "+b+", "+d+", "+e);
					NormalDistribution domesticDistribution = new NormalDistribution(a,b);
					NormalDistribution internationalDistribution = new NormalDistribution(d,e);
					
					if(f.getFlightType() == Flight.FlightType.DOMESTIC){
						distribution = domesticDistribution;
					}
					else if(f.getFlightType() == Flight.FlightType.INTERNATIONAL){
						distribution = internationalDistribution;
					}
					
					Map<Double, Set<Integer>> modMap = new HashMap<Double, Set<Integer>>();
					int passengersAssigned = 0;
					
					for(int t = 0; t < timeWindows.size(); t++){
						double prob;
						
						if(timeWindows.size() == 1){
							prob = distribution.cumulativeProbability(timeWindows.get(0) + timeGranularity - f.getDepartureTime());
						}
						else if(t == 0){
							prob = distribution.cumulativeProbability(timeWindows.get(t + 1) - f.getDepartureTime());
						}
						else if(timeWindows.get(t) < f.getDepartureTime() && t == timeWindows.size() - 1){
							double prob1 = distribution.cumulativeProbability(timeWindows.get(t) - f.getDepartureTime());
							
							prob = 1.0 - prob1;
						}
						else if(timeWindows.get(t) < f.getDepartureTime() && f.getDepartureTime() <= timeWindows.get(t + 1)){
							double prob1 = distribution.cumulativeProbability(timeWindows.get(t) - f.getDepartureTime());
							
							prob = 1.0 - prob1;
						}
						else if(timeWindows.get(t) < f.getDepartureTime()){
							double prob1 = distribution.cumulativeProbability(timeWindows.get(t) - f.getDepartureTime());
							double prob2 = distribution.cumulativeProbability(timeWindows.get(t + 1) - f.getDepartureTime());
							
							prob = prob2 - prob1;
						}
						else{
							continue;
						}
						
						double numPassengers = passengerDistribution.get(c) * prob;
					
						if(!modMap.containsKey(numPassengers % 1.0)){
							modMap.put(numPassengers % 1.0, new HashSet<Integer>());
						}
						
						modMap.get(numPassengers % 1.0).add(timeWindows.get(t));
							
						if(!temporalPassengerDistribution.containsKey(timeWindows.get(t))){
							temporalPassengerDistribution.put(timeWindows.get(t), new HashMap<RiskCategory, Integer>());
						}
							
						temporalPassengerDistribution.get(timeWindows.get(t)).put(c, (int)numPassengers);
							
						passengersAssigned += (int)numPassengers;
					}
					
					List<Double> modList = new ArrayList<Double>(modMap.keySet());
					
					Collections.sort(modList);
					Collections.reverse(modList);
					
					for(double modValue : modList){
						for(Integer timeWindow : modMap.get(modValue)){
							while(passengersAssigned < passengerDistribution.get(c)){
								int currentlyAssigned = temporalPassengerDistribution.get(timeWindow).get(c);
								
								temporalPassengerDistribution.get(timeWindow).put(c, currentlyAssigned + 1);
								passengersAssigned++;
							}
						}
					}
				}
				
				Set<Integer> removeableTimeWindows = new HashSet<Integer>();
				
				for(int timeWindow : temporalPassengerDistribution.keySet()){
					boolean removeTimeWindow = true;
					
					for(RiskCategory c : temporalPassengerDistribution.get(timeWindow).keySet()){
						if(temporalPassengerDistribution.get(timeWindow).get(c) > 0){
							removeTimeWindow = false;
							break;
						}
					}
					
					if(removeTimeWindow){
						removeableTimeWindows.add(timeWindow);
					}	
				}
				
				for(int timeWindow : removeableTimeWindows){
					// temporalPassengerDistribution.remove(timeWindow);
				}

				temporalPassengerDistributionList.add( temporalPassengerDistribution );

				// temporalPassengerDistributionList.add(temporalPassengerDistribution);
			}

			
			for( int i = 0; i < numberSamples; i++){
				
				for(int t : temporalPassengerDistributionList.get(i).keySet()){
					for(RiskCategory c : temporalPassengerDistributionList.get(i).get(t).keySet()){
						if(temporalPassengerDistributionList.get(i).get(t).get(c) >= 0){
							if(!flightMap.containsKey(t)){
								flightMap.put(t, new ArrayList<Flight>());
							}
							if( !flightMap.get(t).contains(f)){	
								flightMap.get(t).add(f);
							}
							break;
						}
					}
				}
			}
			
		f.setTemporalPassengerDistributionList(temporalPassengerDistributionList);
		}
	}
	
	public List<ScreeningOperation> getScreeningOperations(){
		return screeningOperations;
	}
	
	public Map<ScreeningResource, Integer> getScreeningResources(){
		return screeningResources;
	}
	
	public void setScreeningResources(Map<ScreeningResource, Integer> screeningResources){
		this.screeningResources = screeningResources;
	}
	
	public Map<PostScreeningResource, Integer> getPostScreeningResources(){
		return postScreeningResources;
	}
	
	public void setPostScreeningResources(Map<PostScreeningResource, Integer> postScreeningResources){
		this.postScreeningResources = postScreeningResources;
	}
	
	public Map<RiskCategory, Double> getAdversaryDistribution(){
		return adversaryDistribution;
	}
	
	public void setAdversaryDistribution(Map<RiskCategory, Double> adversaryDistribution){
		this.adversaryDistribution = adversaryDistribution;
	}
	
	public Map<Integer, Map<ScreeningResource, Double>> getResourceFines(){
		return resourceFines.getFines();
	}
	
	public void setResourceFines(String dist, double fmin, double fmax, int fnumTests, int thisTest) throws Exception{
		this.resourceFines = new ResourceFines(thisTest);
		this.resourceFines.generateFines(dist, fmin, fmax, fnumTests, thisTest, this);
		
	}
	
	public List<Flight> getFlights(){
		return flights;
	}
	
	public List<Flight> getFlights(int timeWindow){
		return flights;
	}
	
	public void setFlights(List<Flight> flights){
		this.flights = flights;
	}
	
	public List<AttackMethod> getAttackMethods(){
		return attackMethods;
	}
	
	public boolean flightByFlight(){
		return flightByFlight;
	}
	
	public List<Integer> getTimeWindows(){
		// List<Integer> tw = new ArrayList<Integer>(flightMap.keySet());
		
		Collections.sort(timeWindows);
		
		return timeWindows;
	}
	
	public Map<Integer, Map<ScreeningResource, Integer>> getScreeningResourceCapacities(){
		Map<Integer, Map<ScreeningResource, Integer>> screeningResourceCapacities = new HashMap<Integer, Map<ScreeningResource, Integer>>();
		
		for(int t : getTimeWindows()){
			Map<ScreeningResource, Integer> p = new HashMap<ScreeningResource, Integer>();
			
			for(ScreeningResource r : screeningResources.keySet()){
				p.put(r, r.capacity() * screeningResources.get(r));
			}
			
			screeningResourceCapacities.put(t, p);
		}
		
		return screeningResourceCapacities;
	}
	
	public void setXiDistribution(){
		
		this.violDistribution = new ArrayList<PassengerDistribution>();
		
		for(int count = 0; count < numViolProb; count++){
			
			Map<Integer, Map<Flight, Map<RiskCategory, Integer>>> dist = new HashMap<Integer, Map<Flight, Map<RiskCategory, Integer>>>();
			
			for(int t : flightMap.keySet()){
				dist.put(t, new HashMap<Flight, Map<RiskCategory, Integer>>());
			
				for(Flight f : flightMap.get(t)){
					
					List<Map<Integer, Map<RiskCategory, Integer>>> temporalPassengerDistributionList = f.getTemporalPassengerDistributionList();
					
					Map<Integer, Map<RiskCategory, Integer>> temporalDistribution = temporalPassengerDistributionList.get(count);
					
					dist.get(t).put(f, new HashMap<RiskCategory, Integer>());
					
					for(RiskCategory c : temporalDistribution.get(t).keySet()){
						dist.get(t).get(f).put(c, temporalDistribution.get(t).get(c));
					}
				}
			}
			PassengerDistribution passDistribution = new PassengerDistribution(dist);
			// System.out.println(passDistribution);
			violDistribution.add(passDistribution);
			}
		
		this.xiDistribution = new ArrayList<PassengerDistribution>();
		
		for(int count = 0; count < numberSamples; count++){
		
			Map<Integer, Map<Flight, Map<RiskCategory, Integer>>> dist = new HashMap<Integer, Map<Flight, Map<RiskCategory, Integer>>>();
		
			for(int t : flightMap.keySet()){
				dist.put(t, new HashMap<Flight, Map<RiskCategory, Integer>>());
			
				for(Flight f : flightMap.get(t)){
					
					List<Map<Integer, Map<RiskCategory, Integer>>> temporalPassengerDistributionList = f.getTemporalPassengerDistributionList();
					
					Map<Integer, Map<RiskCategory, Integer>> temporalDistribution = temporalPassengerDistributionList.get(count + numViolProb);
					
					dist.get(t).put(f, new HashMap<RiskCategory, Integer>());
					
					for(RiskCategory c : temporalDistribution.get(t).keySet()){
						dist.get(t).get(f).put(c, temporalDistribution.get(t).get(c));
					}
				}
			}
			PassengerDistribution passDistribution = new PassengerDistribution(dist);
			xiDistribution.add(passDistribution);
			}
		System.out.println( "violprobsamples = " + this.violDistribution.size() );
		
	}
	
	public List<PassengerDistribution> getXiDistribution(){
		return xiDistribution;
	}
	
	public List<PassengerDistribution> getViolDistribution(){
		return violDistribution;
	}	
	
	public void setPayoffStructure(){
		Map<Flight, Integer> defCovMap = new HashMap<Flight, Integer>();
		Map<Flight, Integer> defUncovMap = new HashMap<Flight, Integer>();
		Map<Flight, Integer> attCovMap = new HashMap<Flight, Integer>();
		Map<Flight, Integer> attUncovMap = new HashMap<Flight, Integer>();
		
		for(Flight f : flights){
			defCovMap.put(f, f.getDefCovPayoff());
			defUncovMap.put(f, f.getDefUncovPayoff());
			attCovMap.put(f, f.getAttCovPayoff());
			attUncovMap.put(f, f.getAttUncovPayoff());
		}
		
		payoffStructure = new PayoffStructure(defCovMap, defUncovMap, attCovMap, attUncovMap);
	}
	
	public PayoffStructure getPayoffStructure(){
		return payoffStructure;
	}

	private int calcNumberSamples(double epsilon, double beta) {
		// Calculates the number of samples and also states 
		// how many samples will be used to calculate the violation probability
		this.numViolProb = 1000;
		System.out.println("Number of Violation Probability Samples: " + numViolProb );
		
		int numWindows = this.shiftDuration / this.timeGranularity;
		int numRisk = this.adversaryDistribution.keySet().size();
		int numFlights = this.flights.size();
		int numTeams = this.screeningOperations.size();
		
		// nw is number of b variables + number of s variables + number of m variables
		int nw = numFlights * numRisk * numWindows * numTeams + numRisk + numFlights * numRisk * ( (numWindows) * (numWindows - 1)/2 ) * numTeams;
		
		this.decVariables = nw;
		
		if( this.decisionRule.equals("linear") ){
			nw = numFlights * numRisk * numWindows * numTeams + numRisk + numFlights * numRisk * ( (numWindows) * (numWindows - 1)/2 ) * numTeams;
		} else if( this.decisionRule.equals("constant") ){
			nw = numFlights * numRisk * numWindows * numTeams + numRisk;
		}
		
		int N = nw;
		System.out.println("Number of decision variables: "+nw);
		while( true ){
			double sum = 0;
			for( int i = 0 ; i < nw ; ++i ){
				BigDecimal a = new BigDecimal( BigIntegerMath.binomial( N, i ));
				double b = Math.pow(epsilon, i);
				double c = Math.pow((1 - epsilon), (N-i));
				BigDecimal f = new BigDecimal( b* c );
				double d = a.multiply(f).doubleValue();
				sum += d;
				if( sum > beta ){
					break;
				}
				
			}
			
			if( sum <= beta ){
				return N;
			}
			N = N + 1;
		}
	}
}