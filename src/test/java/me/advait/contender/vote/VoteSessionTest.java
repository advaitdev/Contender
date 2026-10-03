package me.advait.contender.vote;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VoteSessionTest {
    private final UUID alice = UUID.randomUUID(), bob = UUID.randomUUID(), carl = UUID.randomUUID(), dana = UUID.randomUUID();

    private VoteSession session() {
        return new VoteSession(VoteSession.number(List.of(Map.entry(alice, "Alice"), Map.entry(bob, "Bob"), Map.entry(carl, "Carl"))), false, 0);
    }

    @Test void numbersCandidatesInOrder() {
        VoteSession session = session();
        assertEquals("Alice", session.byNumber(1).name());
        assertEquals("Carl", session.byNumber(3).name());
        assertNull(session.byNumber(4));
    }

    @Test void rejectsSelfVotes() {
        VoteSession session = session();
        assertThrows(IllegalArgumentException.class, () -> session.vote(alice, session.byNumber(1)));
    }

    @Test void changingAVoteMovesIt() {
        VoteSession session = session();
        session.vote(dana, session.byNumber(1));
        session.vote(dana, session.byNumber(2));
        assertEquals(0, session.votesFor(alice));
        assertEquals(1, session.votesFor(bob));
        assertEquals(1, session.totalVotes());
    }

    @Test void removingACandidateDropsTheirVotes() {
        VoteSession session = session();
        session.vote(dana, session.byNumber(2));
        session.remove(bob);
        assertEquals(0, session.totalVotes());
        assertNull(session.byNumber(2));
        assertThrows(IllegalArgumentException.class, () -> session.vote(carl, session.candidate(bob)));
    }

    @Test void leadersShowTies() {
        VoteSession session = session();
        session.vote(dana, session.byNumber(1));
        session.vote(carl, session.byNumber(2));
        assertEquals(List.of(alice, bob), session.leaders().stream().map(VoteSession.Candidate::id).toList());
        session.vote(alice, session.byNumber(2));
        assertEquals(List.of(bob), session.leaders().stream().map(VoteSession.Candidate::id).toList());
    }

    @Test void nobodyLeadsWithoutVotes() { assertTrue(session().leaders().isEmpty()); }

    @Test void closedSessionsRejectVotes() {
        VoteSession session = session();
        session.close();
        assertThrows(IllegalStateException.class, () -> session.vote(dana, session.byNumber(1)));
    }

    @Test void safePlayersVoteButArentOnTheBallot() {
        // Dana is safe: she votes but isn't a candidate. Carl sits out.
        Map<UUID, String> voters = new java.util.LinkedHashMap<>(Map.of(alice, "Alice", bob, "Bob", dana, "Dana", carl, "Carl"));
        VoteSession session = new VoteSession(VoteSession.number(List.of(Map.entry(alice, "Alice"), Map.entry(bob, "Bob"))), voters, java.util.Set.of(carl), false, 0);
        assertNull(session.candidate(dana));
        assertFalse(session.voters().containsKey(carl));
        assertTrue(session.voters().containsKey(dana));
        session.vote(dana, session.candidate(alice));
        assertEquals(1, session.votesFor(alice));
        assertThrows(IllegalArgumentException.class, () -> session.vote(carl, session.candidate(alice)));
        session.addVoter(carl, "Carl");
        assertFalse(session.voters().containsKey(carl));
    }
}
