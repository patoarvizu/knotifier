package model

import scala.collection.Map
import scala.collection.mutable.HashMap
import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import com.amazonaws.services.autoscaling.model.TagDescription
import scala.collection.JavaConversions._
import actors.AutoScaleModifier
import actors.AutoScalingDataMonitor._
import scala.language.postfixOps
import actors.AutoScalingDataMonitor

case class ReplacementInfo(
        spotGroupName: String,
        autoScalingGroup: AutoScalingGroup,
        newInstances: Int = 1) {

    private final val BaseSpotGroupNameTag: String = "BaseSpotGroupName"
    private final val SystemTag: String = "System"
    private final val LaunchConfigurationSuffix: String = "ASLaunchConfigurationSpot"
    private final val AutoScaleGroupSuffix: String = "ASScalingGroupSpot"

    private val tags: Map[String, String] = {
        autoScalingGroup.getTags map { tagDescription => tagDescription.getKey -> tagDescription.getValue} toMap
    }

    val baseSpotGroupName: String = {
        getBaseName(AutoScaleGroupSuffix)
    }

    val baseLaunchConfigurationName: String = {
        getBaseName(LaunchConfigurationSuffix)
    }

    def getTagValue(key: String): String = {
        tags.getOrElse(key, throw new RuntimeException(s"Key $key doesn't exist"))
    }

    def instanceCount: Int = {
        newInstances;
    }

    private[this] def getBaseName(suffix: String): String = {
        val stackName: String = tags.getOrElse(StackNameTag, throw new RuntimeException(s"$StackNameTag tag is missing"))
        val system: String = tags.getOrElse(SystemTag, throw new RuntimeException(s"$SystemTag tag is missing"))
        s"$stackName-$system$suffix"
    }
}