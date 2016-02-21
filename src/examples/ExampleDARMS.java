package examples;

import models.DARMSModel;
import models.DARMSOutput;
import solvers.DARMSMarginalSolver;
import solvers.DARMSMarginalSolveralt1;
import solvers.MarginalSolverconst;
import solvers.MarginalSolverredo;
import utilities.DARMSHelper;
import utilities.DARMSModelBuilder;

public class ExampleDARMS {
	public static void main(String[] args) {

		try {
			String cplexFile = "CplexConfig";
			DARMSHelper.loadLibrariesCplex(cplexFile); 
			boolean verbose = true;
			String inputFile = "InfeasInputDARMS.30.6.true.txt";
			System.out.println("test1");
			int kk = 0;
			System.out.println( "here" + kk );
		//	while (true) {
				kk = kk + 1;
				System.out.println( kk );
				//
				// int intervals = model.numberTrials;
				// for( int i = 0; i < intervals + 1; ++i ){
				int i = 0;
				boolean zeroSum = true;
				boolean decomposed = false;
				
				String outputFile = "OutputDARMS.txt";

				long start = System.currentTimeMillis();

				if (verbose) {
					System.out.println("Building DARMS model... Started");
				}

				// DARMSModel model = DARMSModelBuilder.buildModel(inputFile,
				//		verbose, i);

				if (verbose) {
					System.out.println("Building DARMS model... Completed");
				}

				DARMSOutput output = new DARMSOutput(outputFile);
				double defenderPayoff = 0;

				DARMSModel model = DARMSModelBuilder.buildModel(inputFile,verbose, 0);
				
				if( model.decisionRule.equals( "constant" ) ){
					MarginalSolverconst solver = new MarginalSolverconst(model,
							zeroSum, decomposed, model.flightByFlight(), false);
					
					if (verbose) {
						System.out.println("Solving DARMS model... Started");
					}

					System.out.println("Saving output file: " + "smallpassdist");
					// solver.writeTemporalPassengerDistribution("./output/smallpassdist" +kk + ".csv");
					
					
					System.out.println("seed = " + model.seed);
					solver.solve();
					solver.calculateViolationProbability();

					if (verbose) {

						String fname = "NumberDecisionVariables.csv";

						double rt = (System.currentTimeMillis() - start) / 1000.0;

						solver.writeProblem("DARMS"+kk+".lp");
						solver.writeSolution("DARMS.sol");

						System.out.println("Solving DARMS model... Completed");

						System.out.println("seed = " + model.seed);

						String[] aa = output.defenderScreeningStrategyFile().split(
								".csv");
						fname = aa[0] + i + ".csv";

						System.out.println("Saving output file: " + fname);
						solver.writeDefenderScreeningStrategy("./output/ScreeningStrategy0"+kk+".csv", rt);

						aa = output.adversaryStrategiesFile().split(".csv");
						fname = aa[0] + i + ".csv";

						// System.out.println("Saving output file: " + fname);
						// solver.writeAdversaryStrategies(fname);

						aa = output.adversaryPayoffsFile().split(".csv");
						fname = aa[0] + i + ".csv";

						// System.out.println("Saving output file: " + fname);
						// solver.writeAdversaryPayoffs(fname);

						aa = output.defenderPayoffsFile().split(".csv");
						fname = aa[0] + i + ".csv";

						System.out.println("Saving output file: " + fname);
						solver.writeDefenderPayoffs(fname);

						aa = output.flightRiskCategoryCoverageFile().split(".csv");
						fname = aa[0] + i + ".csv";

						System.out.println("Saving output file: " + fname);
						solver.writeRiskCategoryCoverage(fname);

						aa = output.passengerDistributionFile().split(".csv");
						fname = aa[0] + i + ".csv";

						System.out.println("Saving output file: " + fname);
						// solver.writeTemporalPassengerDistribution(fname);

						aa = output.resourceFinesFile().split(".csv");
						fname = aa[0] + i + ".csv";

						defenderPayoff = solver.getDefenderPayoff();
					}

					solver.cleanUp();
					
					boolean flightByFlight = model.flightByFlight();
					int numFlights = model.getFlights().size();
					int numCategories = model.getAdversaryDistribution().keySet()
							.size();
					int numTimeWindows = model.getTimeWindows().size();
					double runtime = (System.currentTimeMillis() - start) / 1000.0;

					System.out.println(inputFile + " " + flightByFlight + " "
							+ zeroSum + " " + decomposed + " " + numFlights + " "
							+ numCategories + " " + numTimeWindows + " "
							+ defenderPayoff + " " + runtime + " " + model.seed);
					
					//System.gc();
					
					
					
					
					
					
					
					
					
					
				} else if( model.decisionRule.equals( "linear" ) ) {
					
					DARMSMarginalSolver solver = new DARMSMarginalSolver(model,
						zeroSum, decomposed, model.flightByFlight(), false);
				
					if (verbose) {
						System.out.println("Solving DARMS model... Started");
					}

					System.out.println("Saving output file: " + "smallpassdist");
					// solver.writeTemporalPassengerDistribution("./output/smallpassdist" +kk + ".csv");
					
					
					System.out.println("seed = " + model.seed);
					solver.solve();
					solver.calculateViolationProbability();
					
					if (verbose) {

						String fname = "NumberDecisionVariables.csv";

						double rt = (System.currentTimeMillis() - start) / 1000.0;

						solver.writeProblem("DARMS"+kk+".lp");
						solver.writeSolution("DARMS.sol");

						System.out.println("Solving DARMS model... Completed");

						System.out.println("seed = " + model.seed);

						String[] aa = output.defenderScreeningStrategyFile().split(
								".csv");
						fname = aa[0] + i + ".csv";

						System.out.println("Saving output file: " + fname);
						solver.writeDefenderScreeningStrategy("./output/ScreeningStrategy0"+kk+".csv", rt);

						aa = output.adversaryStrategiesFile().split(".csv");
						fname = aa[0] + i + ".csv";

						// System.out.println("Saving output file: " + fname);
						// solver.writeAdversaryStrategies(fname);

						aa = output.adversaryPayoffsFile().split(".csv");
						fname = aa[0] + i + ".csv";

						// System.out.println("Saving output file: " + fname);
						// solver.writeAdversaryPayoffs(fname);

						aa = output.defenderPayoffsFile().split(".csv");
						fname = aa[0] + i + ".csv";

						System.out.println("Saving output file: " + fname);
						solver.writeDefenderPayoffs(fname);

						aa = output.flightRiskCategoryCoverageFile().split(".csv");
						fname = aa[0] + i + ".csv";

						System.out.println("Saving output file: " + fname);
						solver.writeRiskCategoryCoverage(fname);

						aa = output.passengerDistributionFile().split(".csv");
						fname = aa[0] + i + ".csv";

						System.out.println("Saving output file: " + fname);
						// solver.writeTemporalPassengerDistribution(fname);

						aa = output.resourceFinesFile().split(".csv");
						fname = aa[0] + i + ".csv";

						defenderPayoff = solver.getDefenderPayoff();
					}
					
					solver.cleanUp();
					
					boolean flightByFlight = model.flightByFlight();
					int numFlights = model.getFlights().size();
					int numCategories = model.getAdversaryDistribution().keySet()
							.size();
					int numTimeWindows = model.getTimeWindows().size();
					double runtime = (System.currentTimeMillis() - start) / 1000.0;

					System.out.println(inputFile + " " + flightByFlight + " "
							+ zeroSum + " " + decomposed + " " + numFlights + " "
							+ numCategories + " " + numTimeWindows + " "
							+ defenderPayoff + " " + runtime + " " + model.seed);
					
					//System.gc();
				
				
				}
				
				
		//	}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}