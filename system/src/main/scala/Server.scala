package lila.system

import model._
import lila.chess._
import Pos.posAt

final class Server(repo: GameRepo) {

  def playMove(
    fullId: String,
    moveString: String,
    promString: Option[String] = None): Valid[Map[Pos, List[Pos]]] = for {
    moveParts ← decodeMoveString(moveString) toValid "Wrong move"
    (origString, destString) = moveParts
    orig ← posAt(origString) toValid "Wrong orig " + origString
    dest ← posAt(destString) toValid "Wrong dest " + destString
    promotion ← Role promotable promString toValid "Wrong promotion " + promString
    gameAndPlayer ← repo player fullId toValid "Wrong ID " + fullId
    (game, player) = gameAndPlayer
    chessGame = game.toChess
    newChessGame ← chessGame.playMove(orig, dest, promotion)
    newGame = game update newChessGame
    result ← unsafe { repo save newGame }
  } yield newChessGame.situation.destinations

  def decodeMoveString(moveString: String): Option[(String, String)] = moveString match {
    case MoveString(orig, dest) ⇒ (orig, dest).some
    case _                      ⇒ none
  }
}
