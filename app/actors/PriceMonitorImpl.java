package actors;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import play.Logger;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.handlers.AsyncHandler;
import com.amazonaws.services.ec2.AmazonEC2AsyncClient;
import com.amazonaws.services.ec2.model.DescribeSpotPriceHistoryRequest;
import com.amazonaws.services.ec2.model.DescribeSpotPriceHistoryResult;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.SpotPrice;

public class PriceMonitorImpl implements PriceMonitor
{
    private HashMap<InstanceType, SpotPrice> lowestPrices = new HashMap<InstanceType, SpotPrice>();
    private final List<InstanceType> instanceTypes = getSpotEligibleInstanceTypes();
    private final String[] availabilityZones = new String[] {"us-east-1a", "us-east-1d"};
    private final Object lock = new Object();
    private final AWSCredentials credentials = new ProfileCredentialsProvider().getCredentials();
    private final AmazonEC2AsyncClient ec2ClientAsync = new AmazonEC2AsyncClient(credentials);

    @Override
    public Map<InstanceType, SpotPrice> getPrices()
    {
        return lowestPrices;
    }

    @Override
    public void monitorSpotPrices()
    {
        for(InstanceType instanceType : instanceTypes)
        {
            for(String availabilityZone : availabilityZones)
            {
                DescribeSpotPriceHistoryRequest priceHistoryRequest = new DescribeSpotPriceHistoryRequest();
                priceHistoryRequest.setInstanceTypes(Collections.singleton(instanceType.toString()));
                Calendar calendar = Calendar.getInstance();
                priceHistoryRequest.setEndTime(calendar.getTime());
                calendar.add(Calendar.MINUTE, -5);
                priceHistoryRequest.setStartTime(calendar.getTime());
                priceHistoryRequest.setMaxResults(1);
                priceHistoryRequest.setProductDescriptions(Collections.singleton("Linux/UNIX"));
                priceHistoryRequest.setAvailabilityZone(availabilityZone);
                AsyncHandler<DescribeSpotPriceHistoryRequest, DescribeSpotPriceHistoryResult> asyncHandler = new DescribeSpotPriceHistoryAsyncHandler(instanceType);
                ec2ClientAsync.describeSpotPriceHistoryAsync(priceHistoryRequest, asyncHandler);
            }
        }
        printPrices();
    }

    private List<InstanceType> getSpotEligibleInstanceTypes()
    {
        ArrayList<InstanceType> eligibleInstanceTypes = new ArrayList<InstanceType>();
        for(InstanceType instanceType : InstanceType.values())
        {
            if(!instanceType.toString().equals(InstanceType.T1Micro.toString()))
                eligibleInstanceTypes.add(instanceType);
        }
        return eligibleInstanceTypes;
    }

    private void printPrices()
    {
        Logger.debug(new Date().toString());
        Logger.debug("Unsorted prices:");
        for(SpotPrice spotPrice : getPrices().values())
        {
            Logger.debug(" --- Price for instance type " + spotPrice.getInstanceType() + " in availability zone " + spotPrice.getAvailabilityZone() + " is " + spotPrice.getSpotPrice());
        }
        Logger.debug("----------");
    }

    private class DescribeSpotPriceHistoryAsyncHandler
            implements
            AsyncHandler<DescribeSpotPriceHistoryRequest, DescribeSpotPriceHistoryResult>
    {
        private InstanceType instanceType;
    
        private DescribeSpotPriceHistoryAsyncHandler(InstanceType instanceType)
        {
            this.instanceType = instanceType;
        }
    
        @Override
        public void onError(Exception e)
        {
            Logger.debug(e.getMessage());
        }
    
        @Override
        public void onSuccess(DescribeSpotPriceHistoryRequest priceHistoryRequest,
                DescribeSpotPriceHistoryResult spotPriceHistory)
        {
            for(SpotPrice spotPrice : spotPriceHistory.getSpotPriceHistory())
            {
                synchronized(lock)
                {
                    if(!getPrices().containsKey(spotPrice.getInstanceType())
                           || (Double.valueOf(getPrices().get(instanceType).getSpotPrice()) > Double.valueOf(spotPrice.getSpotPrice())))
                    {
                        getPrices().put(instanceType, spotPrice);
                    }
                }
            }
        }
    }
}