package actors;

import java.util.Map;

import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.SpotPrice;

public interface PriceMonitor
{
    public Map<InstanceType, SpotPrice> getPrices();
    
    public void monitorSpotPrices();
}
