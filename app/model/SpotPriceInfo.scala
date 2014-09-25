package model

import com.amazonaws.services.ec2.model.InstanceType

case class SpotPriceInfo(
         instanceType: InstanceType,
         availabilityZone: String,
         price: Double)