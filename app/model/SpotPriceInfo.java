package model;

import com.amazonaws.services.ec2.model.InstanceType;

public class SpotPriceInfo
{
    public InstanceType instanceType;
    public String availabilityZone;
    public double price;
    
    public SpotPriceInfo(InstanceType instanceType, String availabilityZone, double price)
    {
        this.instanceType = instanceType;
        this.availabilityZone = availabilityZone;
        this.price = price;
    }
    
    public void setSpotPrice(String availabilityZone, double newPrice)
    {
        this.availabilityZone = availabilityZone;
        this.price = newPrice;
    }
}
