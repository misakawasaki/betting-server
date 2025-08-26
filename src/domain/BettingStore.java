package domain;

import domain.model.Bet;
import domain.model.BetOfferId;

import java.util.List;

public interface BettingStore {
    void placeBet(Bet bet);

    List<Bet> queryTopBets(BetOfferId offerId, int limit);

    default List<Bet> queryTop20Bets(BetOfferId offerId) {
        return queryTopBets(offerId, 20);
    }
}