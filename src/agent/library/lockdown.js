import 'ses';

// This sets up the secure environment
// We disable some of the taming to allow for more flexibility

// For configuration, see https://github.com/endojs/endo/blob/master/packages/ses/docs/lockdown.md

const sesLockdown = globalThis.lockdown;
const SesCompartment = globalThis.Compartment;
if (typeof sesLockdown !== 'function' || typeof SesCompartment !== 'function') {
  throw new Error('SES failed to install lockdown/Compartment globals');
}

let lockeddown = false;
export function lockdown() {
  if (lockeddown) return;
  // Call the SES global captured above. Calling `lockdown` here would recurse
  // into this wrapper because the exported function shadows the SES global.
  sesLockdown({
    // basic devex and quality of life improvements
    localeTaming: 'unsafe',
    consoleTaming: 'unsafe',
    errorTaming: 'unsafe',
    stackFiltering: 'verbose',
    // allow eval outside of created compartments
    // (mineflayer dep "protodef" uses eval)
    evalTaming: 'unsafeEval',
  });
  lockeddown = true;
}

export const makeCompartment = (endowments = {}) => {
  return new SesCompartment({
    // provide untamed Math, Date, etc
    Math,
    Date,
    // standard endowments
    ...endowments
  });
};