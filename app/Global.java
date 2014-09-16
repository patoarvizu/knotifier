import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import actors.AutoScaleModifier;
import actors.AutoScaleModifierImpl2;
import actors.PriceMonitor;
import actors.PriceMonitorImpl;
import akka.actor.TypedActor;
import akka.actor.TypedProps;
import play.Application;
import play.GlobalSettings;
import play.libs.Akka;
import scala.concurrent.duration.Duration;

public class Global extends GlobalSettings
{
    public void onStart(Application application)
    {
        AutoScaleModifier autoScaleModifier = TypedActor.get(Akka.system()).typedActorOf(new TypedProps<AutoScaleModifierImpl2>(AutoScaleModifier.class, AutoScaleModifierImpl2.class), "autoScaleModifier");
        PriceMonitor priceMonitor = TypedActor.get(Akka.system()).typedActorOf(new TypedProps<PriceMonitorImpl>(PriceMonitor.class, PriceMonitorImpl.class), "priceMonitor");
        Akka.system().scheduler().schedule(
                Duration.create(20, TimeUnit.SECONDS),
                Duration.create(60, TimeUnit.SECONDS),
                makeRunnable(autoScaleModifier, "monitorAutoScaleGroups"),
                Akka.system().dispatcher()
        );
        Akka.system().scheduler().schedule(
                Duration.create(0, TimeUnit.SECONDS),
                Duration.create(10, TimeUnit.SECONDS),
                makeRunnable(priceMonitor, "monitorSpotPrices"),
                Akka.system().dispatcher()
        );
    }
    
    private Runnable makeRunnable(final Object instance, final String methodName)
    {
        return new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    Method method = instance.getClass().getMethod(methodName);
                    method.invoke(instance);
                }
                catch (Exception e)
                {
                    throw new RuntimeException();
                }
            }
        };
    }
}
