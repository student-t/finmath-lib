package net.finmath.modelling.productfactory;

import java.time.LocalDate;
import java.util.Arrays;

import net.finmath.exception.CalculationException;
import net.finmath.modelling.DescribedProduct;
import net.finmath.modelling.InterestRateProductDescriptor;
import net.finmath.modelling.ProductDescriptor;
import net.finmath.modelling.ProductFactory;
import net.finmath.modelling.descriptor.InterestRateSwapLegProductDescriptor;
import net.finmath.modelling.descriptor.InterestRateSwapProductDescriptor;
import net.finmath.modelling.descriptor.InterestRateSwaptionProductDescriptor;
import net.finmath.montecarlo.RandomVariable;
import net.finmath.montecarlo.interestrate.LIBORModelMonteCarloSimulationInterface;
import net.finmath.montecarlo.interestrate.products.AbstractLIBORMonteCarloProduct;
import net.finmath.montecarlo.interestrate.products.SwapLeg;
import net.finmath.montecarlo.interestrate.products.components.Notional;
import net.finmath.montecarlo.interestrate.products.components.Option;
import net.finmath.montecarlo.interestrate.products.indices.AbstractIndex;
import net.finmath.montecarlo.interestrate.products.indices.LIBORIndex;
import net.finmath.stochastic.RandomVariableInterface;
import net.finmath.time.FloatingpointDate;
import net.finmath.time.ScheduleInterface;

/**
 * Product factory of interest rate derivatives for use with a Monte-Carlo method based model.
 *
 * @author Christian Fries
 * @author Roland Bachl
 */
public class InterestRateMonteCarloProductFactory implements ProductFactory<InterestRateProductDescriptor> {

	private final LocalDate 						referenceDate;

	//	private static final boolean						couponFlow = true;
	//	private static final boolean						isNotionalAccruing = false;

	/**
	 * Initialize the factory with the given referenceDate.
	 *
	 * @param referenceDate To be used when converting absolute dates to relative dates in double.
	 */
	public InterestRateMonteCarloProductFactory(LocalDate referenceDate) {
		super();
		this.referenceDate = referenceDate;
	}

	@Override
	public DescribedProduct<? extends InterestRateProductDescriptor> getProductFromDescriptor(ProductDescriptor descriptor) {

		if(descriptor instanceof InterestRateSwapLegProductDescriptor) {
			InterestRateSwapLegProductDescriptor swapLeg 					= (InterestRateSwapLegProductDescriptor) descriptor;
			DescribedProduct<InterestRateSwapLegProductDescriptor> product 	= new SwapLegMonteCarlo(swapLeg, referenceDate);
			return product;

		}
		else if(descriptor instanceof InterestRateSwapProductDescriptor){
			InterestRateSwapProductDescriptor swap 							= (InterestRateSwapProductDescriptor) descriptor;
			DescribedProduct<InterestRateSwapProductDescriptor> product		= new SwapMonteCarlo(swap, referenceDate);
			return product;

		}
		else if(descriptor instanceof InterestRateSwaptionProductDescriptor) {
			InterestRateSwaptionProductDescriptor swaption						= (InterestRateSwaptionProductDescriptor) descriptor;
			DescribedProduct<InterestRateSwaptionProductDescriptor> product		= new SwaptionPhysicalMonteCarlo(swaption, referenceDate);
			return product;

		}
		else {
			String name = descriptor.name();
			throw new IllegalArgumentException("Unsupported product type " + name);
		}
	}




	/**
	 * Construct a Libor index for a given curve and schedule.
	 *
	 * @param forwardCurveName
	 * @param schedule
	 * @return The Libor index or null, if forwardCurveName is null.
	 */
	private static AbstractIndex constructLiborIndex(String forwardCurveName, ScheduleInterface schedule) {

		if(forwardCurveName != null) {

			//determine average fixing offset and period length
			double fixingOffset = 0;
			double periodLength = 0;

			for(int i = 0; i < schedule.getNumberOfPeriods(); i++) {
				fixingOffset *= ((double) i) / (i+1);
				fixingOffset += (schedule.getPeriodStart(i) - schedule.getFixing(i)) / (i+1);

				periodLength *= ((double) i) / (i+1);
				periodLength += schedule.getPeriodLength(i) / (i+1);
			}

			return new LIBORIndex(forwardCurveName, fixingOffset, periodLength);
		} else {
			return null;
		}
	}

	/**
	 * Monte-Carlo method based implementation of a interest rate swap leg from a product descriptor.
	 *
	 * @author Christian Fries
	 * @author Roland Bachl
	 */
	public static class SwapLegMonteCarlo extends SwapLeg implements DescribedProduct<InterestRateSwapLegProductDescriptor> {

		private static final boolean						couponFlow = true;

		private final InterestRateSwapLegProductDescriptor descriptor;

		/**
		 * Create product from descriptor.
		 *
		 * @param descriptor The descriptor of the product.
		 * @param referenceDate The reference date of the data for the valuation, used to convert absolute date to relative dates in double representation.
		 */
		public SwapLegMonteCarlo(InterestRateSwapLegProductDescriptor descriptor, LocalDate referenceDate) {
			super(descriptor.getLegScheduleDescriptor().getSchedule(referenceDate),
					Arrays.stream(descriptor.getNotionals()).mapToObj(x -> new Notional(x)).toArray(Notional[]::new),
					constructLiborIndex(descriptor.getForwardCurveName(), descriptor.getLegScheduleDescriptor().getSchedule(referenceDate)),
					descriptor.getSpreads(),
					couponFlow,
					descriptor.isNotionalExchanged());

			this.descriptor = descriptor;
		}

		@Override
		public InterestRateSwapLegProductDescriptor getDescriptor() {
			return descriptor;
		}
	}

	/**
	 * Monte-Carlo method based implementation of a interest rate swap from a product descriptor.
	 *
	 * @author Christian Fries
	 * @author Roland Bachl
	 */
	public static class SwapMonteCarlo extends AbstractLIBORMonteCarloProduct implements DescribedProduct<InterestRateSwapProductDescriptor> {

		private final AbstractLIBORMonteCarloProduct legReceiver;
		private final AbstractLIBORMonteCarloProduct legPayer;

		/**
		 * Create product from descriptor.
		 *
		 * @param descriptor The descriptor of the product.
		 * @param referenceDate The reference date of the data for the valuation, used to convert absolute date to relative dates in double representation.
		 */
		public SwapMonteCarlo(InterestRateSwapProductDescriptor descriptor, LocalDate referenceDate) {
			InterestRateMonteCarloProductFactory factory	= new InterestRateMonteCarloProductFactory(referenceDate);
			InterestRateProductDescriptor legDescriptor 	= (InterestRateProductDescriptor) descriptor.getLegReceiver();
			this.legReceiver 								= (AbstractLIBORMonteCarloProduct) factory.getProductFromDescriptor(legDescriptor);
			legDescriptor 									= (InterestRateProductDescriptor) descriptor.getLegPayer();
			this.legPayer 									= (AbstractLIBORMonteCarloProduct) factory.getProductFromDescriptor(legDescriptor);
		}

		@Override
		public RandomVariableInterface getValue(double evaluationTime, LIBORModelMonteCarloSimulationInterface model) throws CalculationException {
			RandomVariableInterface value = new RandomVariable(0);
			if(legPayer != null) {
				value = value.add(legReceiver.getValue(evaluationTime, model));
			}
			if(legPayer != null) {
				value = value.sub(legPayer.getValue(evaluationTime, model));
			}
			return value;
		}

		@SuppressWarnings("unchecked")
		@Override
		public InterestRateSwapProductDescriptor getDescriptor() {
			if(!(legReceiver instanceof DescribedProduct<?>) || !(legPayer instanceof DescribedProduct<?>)) {
				throw new RuntimeException("One or both of the legs of this swap do not support extraction of a descriptor.");
			}
			InterestRateProductDescriptor receiverDescriptor = ((DescribedProduct<InterestRateProductDescriptor>) legReceiver).getDescriptor();
			InterestRateProductDescriptor payerDescriptor = ((DescribedProduct<InterestRateProductDescriptor>) legPayer).getDescriptor();
			return new  InterestRateSwapProductDescriptor(receiverDescriptor, payerDescriptor);
		}
	}

	/**
	 * Monte-Carlo method based implementation of a physically settled interest rate swaption from a product descriptor.
	 *
	 * @author Christian Fries
	 * @author Roland Bachl
	 *
	 */
	public static class SwaptionPhysicalMonteCarlo extends AbstractLIBORMonteCarloProduct
	implements DescribedProduct<InterestRateSwaptionProductDescriptor> {

		private final InterestRateSwaptionProductDescriptor descriptor;

		private final Option swaption;

		/**
		 * Create product from descriptor.
		 *
		 * @param descriptor The descriptor of the product.
		 * @param referenceDate The reference date of the data for the valuation, used to convert absolute date to relative dates in double representation.
		 */
		public SwaptionPhysicalMonteCarlo(InterestRateSwaptionProductDescriptor descriptor, LocalDate referenceDate) {
			super();
			this.descriptor = descriptor;
			double excercise = FloatingpointDate.getFloatingPointDateFromDate(referenceDate, descriptor.getExcerciseDate());
			AbstractLIBORMonteCarloProduct swap = (AbstractLIBORMonteCarloProduct)
					new InterestRateMonteCarloProductFactory(referenceDate).getProductFromDescriptor(descriptor.getUnderlyingSwap());
			swaption = new Option(excercise, descriptor.getStrikeRate(), swap);
		}

		@Override
		public InterestRateSwaptionProductDescriptor getDescriptor() {

			return descriptor;
		}

		@Override
		public RandomVariableInterface getValue(double evaluationTime, LIBORModelMonteCarloSimulationInterface model)
				throws CalculationException {
			return swaption.getValue(evaluationTime, model);
		}
	}
}
