package com.xpl.a2uichat.games.script;
import com.xpl.a2uichat.games.script.IGameResult;
oneway interface IGameSandbox {
    void execute(long id, in byte[] source, in byte[] request, IGameResult callback);
    void cancel(long id);
    void shutdown();
}
