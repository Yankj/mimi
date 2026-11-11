import { copy } from "../../utils/mp-copy";

Component({
  data: {
    title: copy.addChildTitle,
    body: copy.addChildBody,
    action: copy.addChild,
  },
  methods: {
    onAdd() {
      this.triggerEvent("add");
    },
  },
});
