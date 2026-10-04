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

    private VoteSession skipping(VoteSession.Skipping rule) {
        Map<UUID, String> voters = new java.util.LinkedHashMap<>(Map.of(alice, "Alice", bob, "Bob", carl, "Carl", dana, "Dana"));
        return new VoteSession(VoteSession.number(List.of(Map.entry(alice, "Alice"), Map.entry(bob, "Bob"), Map.entry(carl, "Carl"))),
                voters, java.util.Set.of(), rule, false, 0);
    }

    @Test void mostSkipsSavesEveryone() {
        VoteSession session = skipping(VoteSession.Skipping.SAVES);
        session.vote(alice, session.candidate(bob));
        session.skip(carl);
        session.skip(dana);
        assertEquals(2, session.skips());
        assertTrue(session.skipped());
        // Skips aren't a candidate's votes.
        assertEquals(List.of(session.candidate(bob)), session.leaders());
    }

    @Test void aTieWithSkipSavesEveryone() {
        VoteSession session = skipping(VoteSession.Skipping.SAVES);
        session.vote(alice, session.candidate(bob));
        session.skip(carl);
        assertTrue(session.skipped());
        session.vote(dana, session.candidate(bob));
        assertFalse(session.skipped());
    }

    @Test void skipsCanBeIgnoredOrTurnedOff() {
        VoteSession ignored = skipping(VoteSession.Skipping.IGNORED);
        ignored.skip(carl);
        ignored.skip(dana);
        ignored.vote(alice, ignored.candidate(bob));
        assertFalse(ignored.skipped());
        VoteSession off = skipping(VoteSession.Skipping.OFF);
        assertThrows(IllegalArgumentException.class, () -> off.skip(carl));
    }

    @Test void missedVotesCountAgainstTheVoter() {
        // Dana is safe (votes, not on the ballot). Alice votes; Bob, Carl and Dana don't.
        Map<UUID, String> voters = new java.util.LinkedHashMap<>(Map.of(alice, "Alice", bob, "Bob", carl, "Carl", dana, "Dana"));
        VoteSession session = new VoteSession(VoteSession.number(List.of(Map.entry(alice, "Alice"), Map.entry(bob, "Bob"), Map.entry(carl, "Carl"))),
                voters, java.util.Set.of(), false, 0);
        session.vote(alice, session.candidate(bob));
        assertEquals(java.util.Set.of(bob, carl), session.countMissedVotes());
        assertEquals(bob, session.voteOf(bob));
        assertEquals(2, session.votesFor(bob));
        assertEquals(1, session.votesFor(carl));
        assertTrue(session.missed(carl));
        assertFalse(session.missed(alice));
        // A safe player who didn't vote has nobody to count it against.
        assertNull(session.voteOf(dana));
    }
}
