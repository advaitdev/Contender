package me.advait.contender.tournament;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RoundRobinSchedule {
    public record Pairing(int round, int first, int second) { }
    private RoundRobinSchedule() { }

    public static List<Pairing> create(int entries) {
        if (entries < 2 || entries > 64) throw new IllegalArgumentException("Enter between 2 and 64 players or teams.");
        List<Integer> rotation = new ArrayList<>();
        for (int i = 0; i < entries; i++) rotation.add(i);
        if (entries % 2 != 0) rotation.add(-1);
        List<Pairing> matches = new ArrayList<>();
        for (int round = 1; round < rotation.size(); round++) {
            for (int i = 0; i < rotation.size() / 2; i++) {
                int first = rotation.get(i);
                int second = rotation.get(rotation.size() - 1 - i);
                if (first < 0 || second < 0) continue;
                matches.add(round % 2 == 0 ? new Pairing(round, second, first) : new Pairing(round, first, second));
            }
            Collections.rotate(rotation.subList(1, rotation.size()), 1);
        }
        return List.copyOf(matches);
    }
}
