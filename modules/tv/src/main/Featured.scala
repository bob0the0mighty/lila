package lila.tv

import scala.concurrent.duration._

import akka.actor._
import akka.actor.ActorSelection
import akka.pattern.{ ask, pipe }
import chess.Color
import play.twirl.api.Html

import lila.db.api._
import lila.game.tube.gameTube
import lila.game.{ Game, GameRepo }

final class Featured(
    rendererActor: ActorSelection,
    system: ActorSystem) {

  import Featured._

  implicit private def timeout = makeTimeout(50 millis)

  private type Fuog = Fu[Option[Game]]

  private val bus = system.lilaBus

  def one: Fuog =
    (actor ? GetGame mapTo manifest[Option[String]]) recover {
      case _: Exception => none
    } flatMap { _ ?? GameRepo.game }

  private[tv] val actor = system.actorOf(Props(new Actor {

    private var oneId = none[String]

    def receive = {

      case GetGame => sender ! oneId

      case SetGame(game) =>
        oneId = game.id.some
        rendererActor ? actorApi.RenderFeaturedJs(game) onSuccess {
          case html: Html =>
            val msg = lila.socket.Socket.makeMessage(
              "featured",
              play.api.libs.json.Json.obj(
                "html" -> html.toString,
                "id" -> game.id))
            bus.publish(lila.hub.actorApi.game.ChangeFeatured(game.id, msg), 'changeFeaturedGame)
        }
        GameRepo setTv game.id

      case Continue =>
        oneId ?? $find.byId[Game] foreach {
          case None                       => feature foreach elect
          case Some(game) if !fresh(game) => wayBetter(game) orElse rematch(game) orElse featureIfOld(game) foreach elect
          case _                          =>
        }

      case Disrupt =>
        oneId ?? $find.byId[Game] foreach {
          case Some(game) if fresh(game) => wayBetter(game) foreach elect
          case _                         =>
        }
    }

    def elect(gameOption: Option[Game]) {
      gameOption foreach { self ! SetGame(_) }
    }

    def fresh(game: Game) = game.isBeingPlayed && !game.olderThan(15)

    def wayBetter(game: Game): Fuog = feature map {
      case Some(next) if isWayBetter(game, next) => next.some
      case _                                     => none
    }

    def isWayBetter(g1: Game, g2: Game) =
      score(g2.resetTurns) > (score(g1.resetTurns) * 1.1)

    def rematch(game: Game): Fuog = game.next ?? $find.byId[Game]

    def featureIfOld(game: Game): Fuog = (game olderThan 7) ?? feature

    def feature: Fuog = GameRepo.featuredCandidates map { games =>
      Featured.sort(games filter fresh filter Featured.acceptableVariant).headOption
    } orElse GameRepo.random
  }))

  actor ! Continue
}

object Featured {

  private val variants = Set[chess.variant.Variant](
    chess.variant.Standard,
    chess.variant.Chess960,
    chess.variant.KingOfTheHill)

  private case object GetGame
  private case class SetGame(game: Game)
  case object Continue
  case object Disrupt

  def sort(games: List[Game]): List[Game] = games sortBy { -score(_) }

  private def acceptableVariant(g: Game) = variants contains g.variant

  private[tv] def score(game: Game): Int = math.round {
    (heuristics map {
      case (fn, coefficient) => heuristicBox(fn(game)) * coefficient
    }).sum * 1000
  }

  private type Heuristic = Game => Float
  private val heuristicBox = box(0 to 1) _
  private val ratingBox = box(1000 to 2700) _
  private val turnBox = box(1 to 25) _

  private val heuristics: List[(Heuristic, Float)] = List(
    ratingHeuristic(Color.White) -> 1.2f,
    ratingHeuristic(Color.Black) -> 1.2f,
    progressHeuristic -> 0.7f)

  private[tv] def ratingHeuristic(color: Color): Heuristic = game =>
    ratingBox(game.player(color).rating | 1400)

  private[tv] def progressHeuristic: Heuristic = game =>
    1 - turnBox(game.turns)

  // boxes and reduces to 0..1 range
  private[tv] def box(in: Range.Inclusive)(v: Float): Float =
    (math.max(in.start, math.min(v, in.end)) - in.start) / (in.end - in.start).toFloat
}
