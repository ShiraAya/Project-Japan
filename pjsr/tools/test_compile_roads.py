import unittest
from compile_roads import lanes,access
class Fields(unittest.TestCase):
 def test_bidirectional_total(self):self.assertEqual(lanes({'lanes':'4'}),(2,2,False))
 def test_explicit_split(self):self.assertEqual(lanes({'lanes':'3','lanes:forward':'1','lanes:backward':'2'}),(1,2,False))
 def test_reverse(self):self.assertEqual(lanes({'lanes':'2','oneway':'-1'}),(2,0,True))
 def test_specific_access(self):self.assertTrue(access({'access':'no','motor_vehicle':'yes'}))
 def test_specific_denial(self):self.assertFalse(access({'access':'yes','motorcar':'no'}))
 def test_inconsistent(self):
  with self.assertRaises(ValueError):lanes({'lanes':'4','lanes:forward':'3','lanes:backward':'2'})
 def test_shared_one_lane(self):
  with self.assertRaises(ValueError):lanes({'lanes':'1'})
if __name__=='__main__':unittest.main()
