(function() {
    // Basic Touch Drag-and-Drop Polyfill
    // Maps touch events to HTML5 Drag and Drop events
    
    let dragSource = null;
    let dragImage = null;
    let lastTarget = null;
    let dataTransfer = {};
    
    function createDragImage(element, x, y) {
        const clone = element.cloneNode(true);
        clone.style.position = "fixed";
        clone.style.pointerEvents = "none";
        clone.style.opacity = "0.7";
        clone.style.zIndex = "9999";
        clone.style.width = element.offsetWidth + "px";
        clone.style.height = element.offsetHeight + "px";
        clone.style.top = (y - element.offsetHeight / 2) + "px";
        clone.style.left = (x - element.offsetWidth / 2) + "px";
        clone.style.transition = "none";
        
        document.body.appendChild(clone);
        return clone;
    }

    function fireEvent(name, target, relatedTarget, dataTransferData) {
        const evt = document.createEvent("Event");
        evt.initEvent(name, true, true);
        evt.dataTransfer = dataTransferData || {
            data: {},
            setData: function(type, val) { this.data[type] = val; },
            getData: function(type) { return this.data[type]; },
            dropEffect: "move",
            effectAllowed: "all"
        };
        evt.relatedTarget = relatedTarget;
        target.dispatchEvent(evt);
        return evt;
    }

    document.addEventListener("touchstart", function(e) {
        if (e.touches.length > 1) return;
        
        const target = e.target.closest('[draggable="true"]');
        if (!target) return;

        dragSource = target;
        dataTransfer = {
            data: {},
            setData: function(type, val) { this.data[type] = val; },
            getData: function(type) { return this.data[type]; }
        };

        // Delay starting drag to allow for tap/click
        // But for this specific app, dragging usually starts immediately or after a small threshold
        // We'll trust the default behavior and maybe prevent scroll if moved
    }, { passive: false });

    document.addEventListener("touchmove", function(e) {
        if (!dragSource) return;

        const touch = e.touches[0];
        
        if (!dragImage) {
            // Start Drag
            // Prevent default to stop scrolling
            e.preventDefault(); 
            
            const evt = fireEvent("dragstart", dragSource, null, dataTransfer);
            if (evt.defaultPrevented) {
                dragSource = null;
                return;
            }
            
            dragImage = createDragImage(dragSource, touch.clientX, touch.clientY);
        }

        e.preventDefault(); // Stop scrolling

        // Update Ghost Position
        if (dragImage) {
            dragImage.style.top = (touch.clientY - dragImage.offsetHeight / 2) + "px";
            dragImage.style.left = (touch.clientX - dragImage.offsetWidth / 2) + "px";
        }

        // Find drop target
        const element = document.elementFromPoint(touch.clientX, touch.clientY);
        if (element && element !== lastTarget) {
            if (lastTarget) {
                fireEvent("dragleave", lastTarget, element, dataTransfer);
            }
            fireEvent("dragenter", element, lastTarget, dataTransfer);
            lastTarget = element;
        }

        if (element) {
            const evt = fireEvent("dragover", element, null, dataTransfer);
            if (evt.defaultPrevented) {
                // If drop is allowed (preventDefault called on dragover)
                // We can update cursor or visual feedback here if we weren't on mobile
            }
        }
    }, { passive: false });

    document.addEventListener("touchend", function(e) {
        if (!dragSource) return;

        if (dragImage) {
            // We were dragging
            const touch = e.changedTouches[0];
            const element = document.elementFromPoint(touch.clientX, touch.clientY);
            
            if (element) {
                fireEvent("drop", element, null, dataTransfer);
            }
            
            fireEvent("dragend", dragSource, null, dataTransfer);
            
            document.body.removeChild(dragImage);
            dragImage = null;
            lastTarget = null;
        }
        
        dragSource = null;
    });
})();
