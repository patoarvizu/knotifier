package model

import scala.collection.Map
import scala.collection.mutable.HashMap

case class ReplacementInfo(
        baseSpotGroupName: String,
        newInstances: Int = 1,
        originalCapacity: Int,
        tags: Map[String, String]) {
    
    def getTagValue(key: String): String = {
    	tags.getOrElse(key, throw new Exception(s"Key $key doesn't exist"))
    }
    
    def getInstanceCount: Int = {
        newInstances;
    }
}