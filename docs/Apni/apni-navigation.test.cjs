const {test} = require("node:test");
const assert = require("node:assert/strict");
const {create} = require("./apni-navigation.js");

const C = {categories:6, actions:6, addMode:false, windowsMode:false};
function at(radius, angle) {
  const a = angle * Math.PI / 180;
  return [380 + radius * Math.cos(a), 380 + radius * Math.sin(a)];
}
function move(nav, radius, angle, ms, c=C) { return nav.move(...at(radius,angle),ms,c); }

test("fast outer-to-outer diameter preserves active category while selecting distant action", () => {
  const nav=create({active:0});
  move(nav,302,-90,0);
  assert.equal(move(nav,195,90,25).category,0);
  assert.equal(move(nav,50,90,36).category,0);
  assert.equal(move(nav,195,90,47).category,0);
  const final=move(nav,302,90,65);
  assert.equal(final.category,0);
  assert.equal(final.action,3);
  assert.equal(final.transitLocked,false);
});

test("deliberate still hover switches category in 115ms, click switches immediately", () => {
  const nav=create({active:0});
  move(nav,302,-90,0);
  assert.equal(move(nav,175,90,20).category,0);
  assert.equal(nav.settle(115).category,0);
  assert.equal(nav.settle(135).category,3);
  assert.equal(nav.chooseCategory(5).category,5);
  assert.equal(move(nav,180,150,160).category,4); // direct movement within inner ring
});

test("moving through a category does not switch it even if sample time jumps", () => {
  const nav=create({active:1});
  move(nav,302,-30,0);
  move(nav,185,90,20);
  move(nav,170,30,80); // traversed >13px; resets candidate
  assert.equal(nav.settle(136).category,1);
  assert.equal(move(nav,302,150,150).category,1);
});

test("category boundary hysteresis prevents jitter; moving from center can change promptly", () => {
  const nav=create({active:0});
  move(nav,30,0,0);
  assert.equal(move(nav,180,-58,10).category,0); // just across boundary -60deg
  assert.equal(move(nav,180,-50,20).category,1);
});

test("Add Mode gaps outrank categories/actions and never change category on transit", () => {
  const nav=create({active:0}), add={...C,addMode:true};
  move(nav,302,-90,0,add);
  const catGap=move(nav,242,-60,25,add);
  assert.deepEqual(catGap.gap,{which:"cat",at:1});
  assert.equal(catGap.category,0);
  const actionGap=move(nav,302,-60,55,add);
  assert.deepEqual(actionGap.gap,{which:"child",at:1});
  assert.equal(actionGap.action,-1);
  assert.equal(actionGap.category,0);
});

test("empty category and wheel offer deterministic first insertion slots", () => {
  const nav=create();
  const emptyCat={categories:6,actions:0,emptyCategory:true};
  assert.deepEqual(move(nav,302,-90,0,emptyCat).gap,{which:"child",at:0});
  const emptyWheel={categories:0,actions:0,emptyWheel:true};
  assert.deepEqual(move(nav,196,-90,10,emptyWheel).gap,{which:"cat",at:0});
  assert.equal(move(nav,196,-90,10,emptyWheel).category,0);
});

test("clearing or leaving never executes stale outer actions", () => {
  const nav=create();
  assert.equal(move(nav,302,-90,0).action,0);
  assert.equal(move(nav,175,-90,10).action,-1);
  assert.equal(nav.leave().action,-1);
  assert.equal(nav.reset(2).category,2);
  assert.equal(nav.snapshot().gap,null);
});
