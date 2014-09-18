package model;

import java.util.Map;

public class ReplacementInfo
{
    public String launchConfigurationName;
    public int newInstances = 1;
    public int originalCapacity;
    public Map<String, String> tags;

    public ReplacementInfo(String launchConfigurationName, Integer originalCapacity)
    {
        this.launchConfigurationName = launchConfigurationName;
        this.originalCapacity = originalCapacity;
    }
    
    public ReplacementInfo withTags(Map<String, String> tags)
    {
        this.tags = tags;
        return this;
    }
    
    public ReplacementInfo withLaunchConfigurationName(String launchConfigurationName)
    {
        this.launchConfigurationName = launchConfigurationName;
        return this;
    }

    public ReplacementInfo withOriginalCapatity(Integer originalCapacity)
    {
        this.originalCapacity = originalCapacity;
        return this;
    }

    public void increaseInstanceCount()
    {
        newInstances++;
    }
    
    public String getTagValue(String key)
    {
        return tags.get(key);
    }
}