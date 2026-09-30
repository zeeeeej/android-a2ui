package com.xpl.a2uichat.games.script;
oneway interface IGameResult {
    void complete(long id, in byte[] response);
}
