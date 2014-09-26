package model

import scala.collection.Map
import scala.collection.mutable.HashMap

case class ReplacementInfo(
        launchConfigurationName: String,
        newInstances: Int = 1,
        originalCapacity: Int,
        tags: Map[String, String]) {

    def increaseInstanceCount: ReplacementInfo = {
        ReplacementInfo(launchConfigurationName, newInstances + 1, originalCapacity, tags)
    }
    
    def getTagValue(key: String): String = {
    	tags.getOrElse(key, throw new Exception(s"Key $key doesn't exist"))
    }
    
    def getInstanceCount: Int = {
        newInstances;
    }
}