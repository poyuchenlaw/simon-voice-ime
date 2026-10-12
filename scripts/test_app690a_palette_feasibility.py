#!/usr/bin/env python3
"""R2-c feasibility before choosing colors; WCAG22 #dfn-contrast-ratio.

These tests check the specification, not a finished product palette.
Sorted luminances plus .05 must grow at least 3x at every adjacent pair.
The achievable endpoint ratio is at most 21, regardless of hue or theme.
"""
from fractions import Fraction
import unittest


def contrast(lighter, darker):
    return (lighter + Fraction(1, 20)) / (darker + Fraction(1, 20))


class App690APaletteFeasibilityTest(unittest.TestCase):
    def test_wcag_black_white_range(self):
        self.assertEqual(21, contrast(Fraction(1), Fraction(0)))
        self.assertEqual(1, contrast(Fraction(0), Fraction(0)))

    def test_three_colors_have_a_feasible_control(self):
        luminances = (Fraction(0), Fraction(1, 10), Fraction(2, 5))
        self.assertTrue(all(contrast(b, a) >= 3 for a, b in zip(luminances, luminances[1:])))
        self.assertEqual(9, contrast(luminances[-1], luminances[0]))

    def test_four_colors_at_two_to_one_have_a_feasible_control(self):
        luminances = (Fraction(0), Fraction(1, 20), Fraction(3, 20), Fraction(7, 20))
        self.assertTrue(all(contrast(b, a) >= 2 for a, b in zip(luminances, luminances[1:])))
        self.assertEqual(8, contrast(luminances[-1], luminances[0]))

    def test_r2c_four_modes_pairwise_three_to_one_are_feasible(self):
        required_endpoint_ratio = Fraction(3) ** (4 - 1)
        available_endpoint_ratio = contrast(Fraction(1), Fraction(0))
        print(f"R2C_REQUIRED_ENDPOINT={required_endpoint_ratio}:1; WCAG_MAX={available_endpoint_ratio}:1", flush=True)
        self.assertLessEqual(required_endpoint_ratio, available_endpoint_ratio,
                             "R2-c cannot fit four pairwise 3:1 mode colors in WCAG's 1:1..21:1 range")


if __name__ == '__main__':
    unittest.main(verbosity=2)
