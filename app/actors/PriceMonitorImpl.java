package actors;

import static com.amazonaws.services.ec2.model.InstanceType.C32xlarge;
import static com.amazonaws.services.ec2.model.InstanceType.C34xlarge;
import static com.amazonaws.services.ec2.model.InstanceType.C38xlarge;
import static com.amazonaws.services.ec2.model.InstanceType.C3Large;
import static com.amazonaws.services.ec2.model.InstanceType.C3Xlarge;
import static com.amazonaws.services.ec2.model.InstanceType.M32xlarge;
import static com.amazonaws.services.ec2.model.InstanceType.M3Large;
import static com.amazonaws.services.ec2.model.InstanceType.M3Medium;
import static com.amazonaws.services.ec2.model.InstanceType.M3Xlarge;

import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import model.SpotPriceInfo;
import play.Logger;
import util.WeightedPriceCalculator;

import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.SpotPrice;

public class PriceMonitorImpl implements PriceMonitor
{
    private HashMap<InstanceType, SpotPrice> lowestPrices = new HashMap<InstanceType, SpotPrice>();
    private HashMap<InstanceType, SpotPriceInfo> lowestWeightedPrices = new HashMap<InstanceType, SpotPriceInfo>();
    private final List<InstanceType> instanceTypes = getSpotEligibleInstanceTypes();
    private final String[] availabilityZones = new String[] {"us-east-1a", "us-east-1d"};
    private WeightedPriceCalculator weightedPriceCalculator = new WeightedPriceCalculator();

    @Override
    public Map<InstanceType, SpotPrice> getPrices()
    {
        return lowestPrices;
    }

    @Override
    public void monitorSpotPrices()
    {
        ExecutorService threadPool = Executors.newFixedThreadPool(10);
        for(InstanceType instanceType : instanceTypes)
        {
            for(String availabilityZone : availabilityZones)
            {
                Runnable weightedPriceCalculationRunnable = getWeightedPriceCalculationRunnable(instanceType, availabilityZone);
                threadPool.execute(weightedPriceCalculationRunnable);
            }
        }
        printPrices();
    }
    
    private Runnable getWeightedPriceCalculationRunnable(final InstanceType instanceType, final String availabilityZone)
    {
        return new Runnable()
        {
            @Override
            public void run()
            {
                weightedPriceCalculator.addWeightedPrice(instanceType, availabilityZone, lowestWeightedPrices);
            }
        };
    }

    private List<InstanceType> getSpotEligibleInstanceTypes()
    {
        InstanceType[] instanceTypes = { C32xlarge, C34xlarge, C38xlarge, C3Large, C3Xlarge, M32xlarge, M3Large, M3Medium, M3Xlarge };
        return Arrays.asList(instanceTypes);
    }

    private void printPrices()
    {
        Logger.debug(new Date().toString());
        Logger.debug("Unsorted prices:");
        for(SpotPriceInfo spotPrice : lowestWeightedPrices.values())
            Logger.debug(" --- Price for instance type " + spotPrice.instanceType + " in availability zone " + spotPrice.availabilityZone + " is " + spotPrice.price);
        Logger.debug("----------");
    }
}