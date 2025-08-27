import domain.BettingStoreImpl;
import domain.model.Bet;
import domain.model.BetOfferId;
import domain.model.CustomerId;
import domain.model.Stake;
import http.MiniHttpServer;
import http.Session;
import http.SessionManager;
import utils.SimpleSessionKeyGenerator;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

public final class BettingServer {
    private static final int DEFAULT_PORT = 8080;
    private static final BettingStoreImpl bettingStore = new BettingStoreImpl();

    private static void placeBet(int betOfferId, int customerId, int stake) {
        bettingStore.placeBet(
                new Bet(
                    BetOfferId.of(betOfferId),
                    CustomerId.of(customerId),
                    Stake.ofCents(stake)
                ));
    }

    private static String getTop20(int beOfferId) {
        List<Bet> topBets = bettingStore.queryTop20Bets(BetOfferId.of(beOfferId));
        if (topBets.isEmpty()) {
            return "";
        }
        return topBets.stream().map(BettingServer::toCSV).collect(Collectors.joining(","));
    }

    private static String toCSV(Bet bet) {
        return bet.customerId() + "=" +  bet.stake();
    }

    public static void main(String[] args) throws IOException {
        MiniHttpServer server = MiniHttpServer.create(DEFAULT_PORT);

        // register exception handler
        server.exceptionHandler((ctx, e) -> {
            if (e instanceof NumberFormatException) {
                ctx.status(400).text("Invalid number format: " + e.getMessage());
            } else {
                ctx.status(500).text("Server error: " + e.getMessage());
            }
        });


        // register routes
        server.get("/:customerid/session", ctx -> {
            int customerId = Integer.parseInt(ctx.pathParam("customerid"));
            Session session = ctx.session(customerId, true);
            ctx.text(session.sessionKey());
        });

        server.group("/:betofferid/*", () -> {
            server.before("/stake", ctx -> {
                Session sesssion = ctx.session(SimpleSessionKeyGenerator.parsePrefix(ctx.queryParam("sessionkey", "")), false);
                if (sesssion == null || !sesssion.isValid()) {
                    ctx.status(401).text("invalid session");
                    ctx.abort();
                }
            });

            server.post("/stake", ctx -> {
                int customerId = SimpleSessionKeyGenerator.parsePrefix(ctx.queryParam("sessionkey", ""));
                int betOfferId = Integer.parseInt(ctx.pathParam("betofferid"));
                int stake = Integer.parseInt(ctx.getRequestBody());
                placeBet(betOfferId, customerId, stake);
                ctx.status(204);
            });

            server.get("/highstakes", ctx -> {
                int betOfferId = Integer.parseInt(ctx.pathParam("betofferid"));
                ctx.text(getTop20(betOfferId));
            });
        });

        // start server
        server.start();
        System.out.println("Betting Server Started Listening on port " + DEFAULT_PORT);

        // close all
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            SessionManager.getInstance().close();
            bettingStore.close();
        }));
    }
}
