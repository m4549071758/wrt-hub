// Prints the snapshot the agent collects; the test runner compares it with the fixture.
'use strict';

import * as ubus from 'ubus';
import { collect } from 'wrthub.collect';

print(sprintf('%J\n', collect(ubus.connect(), 1795300000000)));
