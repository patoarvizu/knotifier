package model

import scala.collection.Map
import scala.collection.mutable.HashMap
import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import com.amazonaws.services.autoscaling.model.TagDescription
import scala.collection.JavaConversions._
import actors.AutoScaleModifier
import scala.language.postfixOps

case class ReplacementInfo(
        spotGroupName: String,
        autoScalingGroup: AutoScalingGroup,
        newInstances: Int = 1) {

    private val tags: Map[String, String] = {
        val tagDescriptions: Iterable[TagDescription] = autoScalingGroup.getTags
        tagDescriptions map { tagDescription => tagDescription.getKey -> tagDescription.getValue} toMap
    }

    val originalCapacity: Int = autoScalingGroup.getDesiredCapacity

    def getTagValue(key: String): String = {
        tags.getOrElse(key, throw new RuntimeException(s"Key $key doesn't exist"))
    }
    
    def instanceCount: Int = {
        newInstances;
    }

    def baseSpotGroupName: String = {
        tags.getOrElse(AutoScaleModifier.BaseSpotGroupNameTag, throw new RuntimeException(s"${AutoScaleModifier.BaseSpotGroupNameTag} tag is missing"))
    }
}