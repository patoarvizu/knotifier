package actors

import scala.concurrent.ExecutionContext
import play.libs.Akka

trait BaseActor {
    implicit val executor: ExecutionContext = Akka.system().dispatcher;
}