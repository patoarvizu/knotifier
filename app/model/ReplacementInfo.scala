package model

import scala.collection.Map
import scala.collection.mutable.HashMap

class ReplacementInfo(val launchConfigurationName: String, var newInstances: Int = 1, val originalCapacity: Int, val tags: Map[String, String])
{
	def this(launchConfigurationName: String, originalCapacity: Int, tags: Map[String, String]) = {
		this(launchConfigurationName, 1, originalCapacity, tags);
	}
	
    def increaseInstanceCount: Unit = {
    	newInstances = newInstances + 1;
    }
    
    def getTagValue(key: String): String = {
    	tags.getOrElse(key, null);
    }
}