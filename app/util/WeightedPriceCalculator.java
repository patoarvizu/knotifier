package util;

import java.util.Calendar;
import java.util.Collections;
import java.util.Map;

import model.SpotPriceInfo;
import play.Logger;

import com.amazonaws.services.ec2.model.DescribeSpotPriceHistoryRequest;
import com.amazonaws.services.ec2.model.DescribeSpotPriceHistoryResult;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.SpotPrice;

public class WeightedPriceCalculator implements BaseAmazonClient
{
    public void addWeightedPrice(InstanceType instanceType, String availabilityZone, Map<InstanceType,SpotPriceInfo> lowestWeightedPrices)
    {
        double currentPrice = getCurrentPrice(instanceType, availabilityZone);
        double lastDayAverage = getLastDayAverage(instanceType, availabilityZone);
        double threeMonthAverage = getThreeMonthAverage(instanceType, availabilityZone);
        double weightedPrice = currentPrice * .25 + lastDayAverage * .25 + threeMonthAverage * .5;
        weightedPrice = Math.floor(weightedPrice * 10000) / 10000; //Round to four decimals
        synchronized (lowestWeightedPrices)
        {
            if (!lowestWeightedPrices.containsKey(instanceType))
                lowestWeightedPrices.put(instanceType, new SpotPriceInfo(instanceType, availabilityZone, weightedPrice));
            else
                if (lowestWeightedPrices.get(instanceType).price > weightedPrice)
                    lowestWeightedPrices.get(instanceType).setSpotPrice(new String(availabilityZone), weightedPrice);
        }
    }

    private double getThreeMonthAverage(InstanceType instanceType,
            String availabilityZone)
    {
        return getAveragePrice(instanceType, availabilityZone, Calendar.MONTH, 3);
    }

    private double getLastDayAverage(InstanceType instanceType,
            String availabilityZone)
    {
        return getAveragePrice(instanceType, availabilityZone, Calendar.HOUR, 24);
    }

    private double getCurrentPrice(InstanceType instanceType,
            String availabilityZone)
    {
        return getAveragePrice(instanceType, availabilityZone, Calendar.MINUTE, 1);
    }
    
    private double getAveragePrice(InstanceType instanceType, String availabilityZone, int calendarField, int period)
    {
        DescribeSpotPriceHistoryRequest priceHistoryRequest = new DescribeSpotPriceHistoryRequest();
        priceHistoryRequest.setInstanceTypes(Collections.singleton(instanceType.toString()));
        Calendar calendar = Calendar.getInstance();
        priceHistoryRequest.setEndTime(calendar.getTime());
        calendar.add(calendarField, -period);
        priceHistoryRequest.setStartTime(calendar.getTime());
        priceHistoryRequest.setProductDescriptions(Collections.singleton("Linux/UNIX"));
        priceHistoryRequest.setAvailabilityZone(availabilityZone);
        DescribeSpotPriceHistoryResult spotPriceHistory;
        double accumulator = 0.0;
        int numberOfResults = 0;
        do
        {
            spotPriceHistory = ec2ClientAsync.describeSpotPriceHistory(priceHistoryRequest);
            numberOfResults += spotPriceHistory.getSpotPriceHistory().size();
            for(SpotPrice spotPrice : spotPriceHistory.getSpotPriceHistory())
                accumulator += Double.valueOf(spotPrice.getSpotPrice());
            priceHistoryRequest.setNextToken(spotPriceHistory.getNextToken());
        }
        while(!"".equals(spotPriceHistory.getNextToken()));
        if(numberOfResults == 0)
            return Double.MAX_VALUE;
        return accumulator / numberOfResults;
    }
}