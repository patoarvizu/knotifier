package util

import java.util.ArrayList

import scala.collection.immutable.HashMap

import org.specs2.mutable.Specification

import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import com.amazonaws.services.autoscaling.model.LaunchConfiguration
import com.amazonaws.services.autoscaling.model.TagDescription

import util.NameHelper.AutoScaleGroupSuffix
import util.NameHelper.AvailabilityZoneTag
import util.NameHelper.StackNameTag
import util.NameHelper.SystemTag

class NameHelperTest extends Specification {
    val nameHelper = new NameHelper
    "An auto scaling group map index" should {
        "Be its CloudFormation generated id" in {
            val autoScalingGroup = new AutoScalingGroup
            autoScalingGroup.setTags(new ArrayList)
            val fakeName = "stackId-resourceId-123456ABCDEF";
            autoScalingGroup.setAutoScalingGroupName(fakeName)
            val mapIndex = nameHelper.getAutoScalingGroupsMapIndex(autoScalingGroup)
            mapIndex mustEqual("stackId-resourceId")
        }
        "Be the name based on the stack name, system and availability zone tags" in {
            val autoScalingGroup = new AutoScalingGroup
            val tags = new ArrayList[TagDescription]
            val stackNameTag = new TagDescription
            stackNameTag.setKey(StackNameTag)
            stackNameTag.setValue("stackId")
            tags.add(stackNameTag)
            val systemTag = new TagDescription
            systemTag.setKey(SystemTag)
            systemTag.setValue("system")
            tags.add(systemTag)
            val availabilityZoneTag = new TagDescription
            availabilityZoneTag.setKey(AvailabilityZoneTag )
            availabilityZoneTag.setValue("availabilityZone")
            tags.add(availabilityZoneTag)
            autoScalingGroup.setTags(tags)
            val mapIndex = nameHelper.getAutoScalingGroupsMapIndex(autoScalingGroup)
            mapIndex mustEqual(s"stackId-system$AutoScaleGroupSuffix-availabilityZone")
        }
    }
    "An autoscaling group name" should {
        "Include the correct availability zone" in {
            val groupNameWithAvailabilityZone = nameHelper.getAutoScalingGroupNameWithAvailabilityZone("baseGroupName", "availabilityZone")
            groupNameWithAvailabilityZone mustEqual("baseGroupName-availabilityZone")
        }
    }
    "A launch configuration map index" should {
        "Not include the ARN id if it's CloudFormation-generated" in {
            val launchConfiguration = new LaunchConfiguration
            launchConfiguration.setLaunchConfigurationName("stackId-resourceId-123456ABCDEF")
            val mapIndex = nameHelper.getLaunchConfigurationsMapIndex(launchConfiguration)
            mapIndex mustEqual("stackId-resourceId")
        }
        "Be the exact launch configuration name if it's not CloudFormation-generated" in {
            val launchConfiguration = new LaunchConfiguration
            launchConfiguration.setLaunchConfigurationName("stackId-resourceId-nonCloudFormation")
            val mapIndex = nameHelper.getLaunchConfigurationsMapIndex(launchConfiguration)
            mapIndex mustEqual("stackId-resourceId-nonCloudFormation")
        }
    }
    "A launch configuration name" should {
        "Include the instance type" in {
            val launchConfigurationNameWithInstanceType =
                nameHelper.getLaunchConfigurationNameWithInstanceType("baseLaunchConfigurationName", "instanceType")
            launchConfigurationNameWithInstanceType mustEqual("baseLaunchConfigurationName-instanceType")
        }
    }
    "A resource base name" should {
        "Be the concatenation of the name tag, the system tag and a suffix" in {
            val tags = HashMap[String, String](StackNameTag -> "stackId", SystemTag -> "system")
            val baseName = nameHelper.getBaseName(tags, "suffix")
            baseName mustEqual("stackId-systemsuffix")
        }
    }
}