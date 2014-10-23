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
import util.NameHelper
import util.NameHelper._

object ReplacementInfo {
    val nameHelper = new NameHelper
}

import ReplacementInfo._

case class ReplacementInfo(
        spotGroupName: String,
        autoScalingGroup: AutoScalingGroup,
        newInstances: Int = 1) {

    private val tags: Map[String, String] = {
        autoScalingGroup.getTags map { tagDescription => tagDescription.getKey -> tagDescription.getValue} toMap
    }

    val baseSpotGroupName: String = {
        nameHelper.getBaseName(tags, NameHelper.AutoScaleGroupSuffix)
    }

    val baseLaunchConfigurationName: String = {
        nameHelper.getBaseName(tags, NameHelper.LaunchConfigurationSuffix)
    }

    def getTagValue(key: String): String = {
        tags.getOrElse(key, throw new RuntimeException(s"Key $key doesn't exist"))
    }

    def instanceCount: Int = {
        newInstances;
    }
}