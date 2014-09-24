import play.GlobalSettings
import play.Application
import play.Logger
import actors.AutoScaleModifier
import akka.actor.TypedActor
import play.libs.Akka
import akka.actor.TypedProps
import actors.AutoScaleModifierImpl
import actors.AutoScalingDataMonitorImpl
import actors.AutoScalingDataMonitor
import actors.PriceMonitor
import actors.PriceMonitorImpl
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

class Global extends GlobalSettings {

    override def onStart(app: Application) {
        implicit val executor: ExecutionContext = Akka.system().dispatcher;
        val priceMonitor: PriceMonitor = TypedActor(Akka.system()).typedActorOf(TypedProps[PriceMonitorImpl](), "priceMonitor");
        val autoScalingDataMonitor: AutoScalingDataMonitor = TypedActor(Akka.system()).typedActorOf(TypedProps[AutoScalingDataMonitorImpl](), "autoScalingDataMonitor");
        val autoScaleModifier: AutoScaleModifier = TypedActor(Akka.system()).typedActorOf(TypedProps[AutoScaleModifierImpl](), "autoScaleModifier");
    	Akka.system().scheduler.schedule(0.seconds, 10.seconds)({priceMonitor.monitorSpotPrices})
    	Akka.system().scheduler.schedule(30.seconds, 30.seconds)({autoScaleModifier.monitorAutoScaleGroups});
    	Akka.system().scheduler.schedule(0.seconds, 10.seconds)({autoScalingDataMonitor.monitorAutoScalingData});
    }
}