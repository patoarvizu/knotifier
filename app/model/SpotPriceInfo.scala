package model

import com.amazonaws.services.ec2.model.InstanceType

class SpotPriceInfo(val instanceType: InstanceType, var availabilityZone: String, var price: Double) {

	def setSpotPrice(availabilityZone: String, newPrice: Double): Unit = {
        this.availabilityZone = availabilityZone;
        this.price = newPrice;
    }
}