package actors;

import handlers.BaseAsyncHandler;

import java.util.HashMap;
import java.util.Map;

import play.Logger;

import com.amazonaws.services.autoscaling.model.AutoScalingGroup;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsResult;
import com.amazonaws.services.autoscaling.model.DescribeLaunchConfigurationsRequest;
import com.amazonaws.services.autoscaling.model.DescribeLaunchConfigurationsResult;
import com.amazonaws.services.autoscaling.model.LaunchConfiguration;

public class AutoScalingDataMonitorImpl implements AutoScalingDataMonitor
{
    private Map<String, AutoScalingGroup> autoScalingGroups = new HashMap<String, AutoScalingGroup>();
    private Map<String, LaunchConfiguration> launchConfigurations = new HashMap<String, LaunchConfiguration>();

    @Override
    public void monitorAutoScalingData()
    {
        asClient.describeAutoScalingGroupsAsync(new DescribeAutoScalingGroupsRequest(), new BaseAsyncHandler<DescribeAutoScalingGroupsRequest, DescribeAutoScalingGroupsResult>()
        {
            public void onSuccess(DescribeAutoScalingGroupsRequest request, DescribeAutoScalingGroupsResult result)
            {
                for(AutoScalingGroup autoScalingGroup : result.getAutoScalingGroups())
                    autoScalingGroups.put(autoScalingGroup.getAutoScalingGroupName(), autoScalingGroup);
            };
        });
        asClient.describeLaunchConfigurationsAsync(new DescribeLaunchConfigurationsRequest(), new BaseAsyncHandler<DescribeLaunchConfigurationsRequest, DescribeLaunchConfigurationsResult>()
        {
    
            @Override
            public void onSuccess(DescribeLaunchConfigurationsRequest request,
                    DescribeLaunchConfigurationsResult result)
            {
                for(LaunchConfiguration launchConfiguration : result.getLaunchConfigurations())
                    launchConfigurations.put(launchConfiguration.getLaunchConfigurationName(), launchConfiguration);
            }
        });
    }

    @Override
    public void updateAutoScalingGroupsData()
    {
        DescribeAutoScalingGroupsResult autoScalingGroupsResult = asClient.describeAutoScalingGroups();
        for(AutoScalingGroup autoScalingGroup : autoScalingGroupsResult.getAutoScalingGroups())
            autoScalingGroups.put(autoScalingGroup.getAutoScalingGroupName(), autoScalingGroup);
        Logger.debug("Auto scaling groups updated!");
    }
    
    @Override
    public void updateLaunchConfigurationsData()
    {
        DescribeLaunchConfigurationsResult launchConfigurationsResult = asClient.describeLaunchConfigurations();
        for(LaunchConfiguration launchConfiguration : launchConfigurationsResult.getLaunchConfigurations())
            launchConfigurations.put(launchConfiguration.getLaunchConfigurationName(), launchConfiguration);
        Logger.debug("Launch configurations updated!");
    }

    @Override
    public Map<String, AutoScalingGroup> getAutoScaleData()
    {
        return autoScalingGroups;
    }

    @Override
    public Map<String, LaunchConfiguration> getLaunchConfigurationData()
    {
        return launchConfigurations;
    }
}