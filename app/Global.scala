import play.GlobalSettings
import play.Application
import play.Logger
import actors.AutoScaleModifier
import akka.actor.TypedActor
import play.libs.Akka
import akka.actor.TypedProps
import actors.AutoScalingDataMonitor
import actors.PriceMonitor
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class Global extends GlobalSettings {

    override def onStart(app: Application) {
        Akka.system.scheduler.schedule(0.seconds, 30.seconds) {
            Future { PriceMonitor.monitorSpotPrices }
        }
        Akka.system.scheduler.schedule(30.seconds, 30.seconds) {
            Future { AutoScaleModifier.monitorAutoScaleGroups } 
        }
        Akka.system.scheduler.schedule(0.seconds, 30.seconds) {
            Future { AutoScalingDataMonitor.monitorAutoScalingData }
        }
    }
}