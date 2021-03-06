var Chess = require('chessli.js').Chess;
var game = require('game').game;
var status = require('game').status;
var xhr = require('../xhr');

module.exports = function(root) {

  this.root = root;
  this.active = false;
  this.broken = false;
  this.ply = 0;

  this.vm = {
    hash: ''
  };

  var situationCache = {};

  var gameVariants = {
    'chess960' : 1,
    'antichess': 2,
    'atomic': 3
  };

  var showFen = function() {
    try {
      var ply, move, cached, fen, hash, h, lm;
      for (ply = 1; ply <= this.ply; ply++) {
        move = root.data.game.moves[ply - 1];
        h += move;
        cached = situationCache[h];
        if (!cached) break;
        hash = h;
        fen = cached.fen;
      }
      if (!cached || ply < this.ply) {
        var chess = new Chess(
          fen || root.data.game.initialFen,
          gameVariants[root.data.game.variant.key] || 0);
        for (ply = ply; ply <= this.ply; ply++) {
          move = root.data.game.moves[ply - 1];
          hash += move;
          lm = chess.move(move);
          situationCache[hash] = {
            fen: chess.fen(),
            check: chess.in_check(),
            lastMove: [lm.from, lm.to],
            turnColor: chess.turn() == 'w' ? 'white' : 'black'
          };
        }
      }
      root.chessground.set(situationCache[hash]);
    } catch (e) {
      console.log(e);
      onBreak();
    }
  }.bind(this);

  var enable = function() {
    root.chessground.stop();
  }.bind(this);

  var disable = function() {
    this.ply = 0;
    root.chessground.set({
      movable: {
        color: game.isPlayerPlaying(root.data) ? root.data.player.color : null,
        dests: game.parsePossibleMoves(root.data.possibleMoves)
      }
    });
  }.bind(this);

  var onBreak = function() {
    disable();
    this.active = false;
    this.broken = true;
    xhr.reload(root).then(root.reload);
  }.bind(this);

  this.jump = function(ply) {
    if (this.broken) return;
    if (this.ply == ply || ply < 1 || ply > root.data.game.moves.length) return;
    this.active = ply != root.data.game.moves.length;
    this.ply = ply;
    showFen();
    if (this.active) enable();
    else disable();
  }.bind(this);

  this.onReload = function(cfg) {
    if (this.active && cfg.game.moves.join() != root.data.game.moves.join()) this.active = false;
    this.vm.hash = null;
  }.bind(this);

  this.enabledByPref = function() {
    var d = root.data;
    return d.pref.replay === 2 || (
      d.pref.replay === 1 && (d.game.speed === 'classical' || d.game.speed === 'unlimited')
    );
  }.bind(this);

  this.isLate = function() {
    return this.active && status.playing(root.data);
  }.bind(this);
}
