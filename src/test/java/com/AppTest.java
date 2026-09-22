package com;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AppTest {

    @Test
    void averageIsComputed() {
        assertEquals(3.0, App.average(1, 2, 6));
    }

    @Test
    void emptyInputIsRejected() {
        assertThrows(IllegalArgumentException.class, App::average);
    }
}
