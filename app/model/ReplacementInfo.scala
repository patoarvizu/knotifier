package model

import scala.collection.Map
import scala.collection.mutable.HashMap

case class ReplacementInfo(
        launchConfigurationName: String,
        var newInstances: Int = 1,
        originalCapacity: Int,
        tags: Map[String, String]) {
    
    def increaseInstanceCount: Unit = {
    	newInstances = newInstances + 1
    }
    
    def getTagValue(key: String): String = {
    	tags.getOrElse(key, throw new Exception("Key " + key + " doesn't exist"))
    }
}