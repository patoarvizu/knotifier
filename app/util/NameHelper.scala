package util

import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import com.amazonaws.services.autoscaling.model.TagDescription
import scala.collection.JavaConversions._
import scala.util.matching.Regex
import com.amazonaws.services.autoscaling.model.LaunchConfiguration
import com.amazonaws.services.ec2.model.InstanceType
import scala.collection.Map

object NameHelper {
    final val PreferredTypesTag: String = "PreferredTypes"
    final val SpotPriceTag: String = "SpotPrice"
    final val SystemTag: String = "System";
    final val StackNameTag: String = "aws:cloudformation:stack-name"
    final val AvailabilityZoneTag: String = "AvailabilityZone"
    final val AutoScaleGroupSuffix: String = "ASScalingGroupSpot"
    final val BaseSpotGroupNameTag: String = "BaseSpotGroupName"
    final val LaunchConfigurationSuffix: String = "ASLaunchConfigurationSpot"
}

import NameHelper._

class NameHelper {

    def getAutoScalingGroupsMapIndex(autoScalingGroup: AutoScalingGroup): String = {
        val tags: Iterable[TagDescription] = autoScalingGroup.getTags
        val stackName: String = getTagValue(tags, StackNameTag)
        val system: String = getTagValue(tags, SystemTag)
        val availabilityZone: String = getTagValue(tags, AvailabilityZoneTag)
        if(stackName.isEmpty || system.isEmpty || availabilityZone.isEmpty)
            stripARNid(autoScalingGroup.getAutoScalingGroupName)
        else
            s"$stackName-$system$AutoScaleGroupSuffix-$availabilityZone"
    }

    def getLaunchConfigurationsMapIndex(launchConfiguration: LaunchConfiguration): String = {
        stripARNid(launchConfiguration.getLaunchConfigurationName);
    }
    
    def getLaunchConfigurationNameWithInstanceType(baseLaunchConfigurationName: String, instanceType: String): String = {
        s"$baseLaunchConfigurationName-$instanceType"
    }
    
    def getAutoScalingGroupNameWithAvailabilityZone(baseGroupName: String, availabilityZone: String): String = {
        s"$baseGroupName-$availabilityZone"
    }

    def getBaseName(tags: Map[String, String], suffix: String): String = {
        val stackName: String = tags.getOrElse(StackNameTag, throw new RuntimeException(s"$StackNameTag tag is missing"))
        val system: String = tags.getOrElse(SystemTag, throw new RuntimeException(s"$SystemTag tag is missing"))
        s"$stackName-$system$suffix"
    }

    private[this] def getTagValue(tags: Iterable[TagDescription], tagKey: String): String = {
        val tagDescription: Option[TagDescription] = (tags find({tagDescription: TagDescription => tagDescription.getKey == tagKey }))
        tagDescription match {
            case Some(tagDescription) => tagDescription.getValue()
            case None => ""
        }
    }
    
    private[this] def stripARNid(name: String): String = {
        val idRegex = new Regex("""(.*)(-[0-9A-Z]{12,13})$""", "name", "ARNid")
        idRegex findFirstMatchIn name match {
            case Some(matched) => matched.group("name")
            case None => name
        }
    }
}