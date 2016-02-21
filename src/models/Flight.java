package models;

import java.util.List;
import java.util.Map;

public class Flight {
	private int id;
	private String description;
	private int defUncovPayoff;
	private int defCovPayoff;
	private int attUncovPayoff;
	private int attCovPayoff;
	private int departureTime;
	private FlightType flightType;
	
	private Map<RiskCategory, Integer> passengerDistribution;
	private List< Map<Integer, Map<RiskCategory, Integer >>> temporalPassengerDistributionList;
	
	public static enum FlightType {DOMESTIC, INTERNATIONAL};
	
	private static int ID = 1;
	
	public Flight(String description, FlightType flightType, int departureTime, Map<RiskCategory, Integer> categoryDistribution){
		this.id = ID;
		this.description = description;
		this.flightType = flightType;
		this.departureTime = departureTime;
		this.passengerDistribution = categoryDistribution;
		
		ID++;
	}
	
	public int id(){
		return id;
	}
	
	public void setPayoffs(int defUncovPayoff, int defCovPayoff, int attUncovPayoff, int attCovPayoff){
		this.defUncovPayoff = defUncovPayoff;
		this.defCovPayoff = defCovPayoff;
		this.attUncovPayoff = attUncovPayoff;
		this.attCovPayoff = attCovPayoff;
	}
	
	public void setTemporalPassengerDistributionList(List< Map<Integer, Map<RiskCategory, Integer>>> temporalPassengerDistributionList){
		this.temporalPassengerDistributionList = temporalPassengerDistributionList;
	}

	public List<Map<Integer, Map<RiskCategory, Integer>>> getTemporalPassengerDistributionList(){
		return temporalPassengerDistributionList;
	}
	
	public int getDefUncovPayoff(){
		return defUncovPayoff;
	}
	
	public int getDefCovPayoff(){
		return defCovPayoff;
	}
	
	public int getAttUncovPayoff(){
		return attUncovPayoff;
	}
	
	public int getAttCovPayoff(){
		return attCovPayoff;
	}
		
	public int getDepartureTime(){
		return departureTime;
	}
	
	public Map<RiskCategory, Integer> getPassengerDistribution(){
		return passengerDistribution;
	}
	
	public FlightType getFlightType(){
		return flightType;
	}
	
	public String toString(){
		return description;
	}
	
	public static void reset(){
		ID = 1;
	}
}