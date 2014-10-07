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
        Akka.system.scheduler.schedule(0.seconds, 10.seconds) {
            makeFuture(AutoScaleModifier.monitorAutoScaleGroups)
        }
        Akka.system.scheduler.schedule(0.seconds, 60.seconds) {
            makeFuture(PriceMonitor.monitorSpotPrices)
        }
        Akka.system.scheduler.schedule(0.seconds, 15.seconds) {
            makeFuture(PriceMonitor.printPrices)
        }
        Akka.system.scheduler.schedule(0.seconds, 60.seconds) {
            makeFuture(AutoScalingDataMonitor.monitorAutoScalingData)
        }
    }

    private[this] def makeFuture(action: Unit): Unit = {
        val rethrowException: PartialFunction[Throwable, Unit] = { case e => throw e }
        Future { action } onFailure rethrowException
    }
}